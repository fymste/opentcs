/**
 * Copyright (c) The openTCS Authors.
 *
 * This program is free software and subject to the MIT license. (For details,
 * see the licensing information (LICENSE.txt) you should have received with
 * this copy of the software.)
 */
package org.opentcs.virtualvehicle;

import com.google.inject.assistedinject.Assisted;
import java.beans.PropertyChangeEvent;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import static java.util.Objects.requireNonNull;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import org.opentcs.common.LoopbackAdapterConstants;
import org.opentcs.customizations.kernel.KernelExecutor;
import org.opentcs.data.model.Vehicle;
import org.opentcs.data.order.Route.Step;
import org.opentcs.drivers.vehicle.BasicVehicleCommAdapter;
import org.opentcs.drivers.vehicle.LoadHandlingDevice;
import org.opentcs.drivers.vehicle.MovementCommand;
import org.opentcs.drivers.vehicle.SimVehicleCommAdapter;
import org.opentcs.drivers.vehicle.VehicleCommAdapter;
import org.opentcs.drivers.vehicle.VehicleProcessModel;
import org.opentcs.drivers.vehicle.management.VehicleProcessModelTO;
import org.opentcs.drivers.vehicle.messages.SetSpeedMultiplier;
import org.opentcs.util.ExplainedBoolean;
import org.opentcs.virtualvehicle.VelocityController.WayEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link VehicleCommAdapter} that does not really communicate with a physical vehicle but roughly
 * simulates one.
 *
 * @author Stefan Walter (Fraunhofer IML)
 */
public class LoopbackCommunicationAdapter
    extends BasicVehicleCommAdapter
    implements SimVehicleCommAdapter {

  /**
   * The name of the load handling device set by this adapter.
   */
  public static final String LHD_NAME = "default";
  /**
   * This class's Logger.
   */
  private static final Logger LOG = LoggerFactory.getLogger(LoopbackCommunicationAdapter.class);
  /**
   * An error code indicating that there's a conflict between a load operation and the vehicle's
   * current load state.
   */
  private static final String LOAD_OPERATION_CONFLICT = "cannotLoadWhenLoaded";
  /**
   * An error code indicating that there's a conflict between an unload operation and the vehicle's
   * current load state.
   */
  private static final String UNLOAD_OPERATION_CONFLICT = "cannotUnloadWhenNotLoaded";
  /**
   * The time by which to advance the velocity controller per step (in ms).
   */
  private static final int ADVANCE_TIME = 100;
  /**
   * This instance's configuration.
   */
  private final VirtualVehicleConfiguration configuration;
  /**
   * The boolean flag to check if execution of the next command is allowed.
   */
  private boolean singleStepExecutionAllowed;
  /**
   * The vehicle to this comm adapter instance.
   */
  private final Vehicle vehicle;
  /**
   * The vehicle's load state.
   */
  private LoadState loadState = LoadState.EMPTY;
  /**
   * Whether the loopback adapter is initialized or not.
   */
  private boolean initialized;
  /**
   * The amount of time that passed during the simulation of an operation.
   */
  private int operationSimulationTimePassed;

  /**
   * Creates a new instance.
   *
   * @param configuration This class's configuration.
   * @param vehicle The vehicle this adapter is associated with.
   * @param kernelExecutor The kernel's executor.
   */
  @Inject
  public LoopbackCommunicationAdapter(VirtualVehicleConfiguration configuration,
                                      @Assisted Vehicle vehicle,
                                      @KernelExecutor ScheduledExecutorService kernelExecutor) {
    super(new LoopbackVehicleModel(vehicle),
          configuration.commandQueueCapacity(),
          1,
          configuration.rechargeOperation(),
          kernelExecutor);
    this.vehicle = requireNonNull(vehicle, "vehicle");
    this.configuration = requireNonNull(configuration, "configuration");
  }

  @Override
  public void initialize() {
    if (isInitialized()) {
      return;
    }
    super.initialize();

    String initialPos
        = vehicle.getProperties().get(LoopbackAdapterConstants.PROPKEY_INITIAL_POSITION);
    if (initialPos != null) {
      initVehiclePosition(initialPos);
    }
    getProcessModel().setVehicleState(Vehicle.State.IDLE);
    initialized = true;
  }

  @Override
  public boolean isInitialized() {
    return initialized;
  }

  @Override
  public void terminate() {
    if (!isInitialized()) {
      return;
    }
    super.terminate();
    initialized = false;
  }

  @Override
  public void propertyChange(PropertyChangeEvent evt) {
    super.propertyChange(evt);

    if (!((evt.getSource()) instanceof LoopbackVehicleModel)) {
      return;
    }
    if (Objects.equals(evt.getPropertyName(),
                       VehicleProcessModel.Attribute.LOAD_HANDLING_DEVICES.name())) {
      if (!getProcessModel().getVehicleLoadHandlingDevices().isEmpty()
          && getProcessModel().getVehicleLoadHandlingDevices().get(0).isFull()) {
        loadState = LoadState.FULL;
      }
      else {
        loadState = LoadState.EMPTY;
      }
    }
  }

  @Override
  public synchronized void enable() {
    if (isEnabled()) {
      return;
    }
    getProcessModel().getVelocityController().addVelocityListener(getProcessModel());
    super.enable();
  }

  @Override
  public synchronized void disable() {
    if (!isEnabled()) {
      return;
    }
    getProcessModel().getVelocityController().removeVelocityListener(getProcessModel());
    super.disable();
  }

  @Override
  public LoopbackVehicleModel getProcessModel() {
    return (LoopbackVehicleModel) super.getProcessModel();
  }

  @Override
  public synchronized void sendCommand(MovementCommand cmd) {
    requireNonNull(cmd, "cmd");

    // Reset the execution flag for single-step mode.
    singleStepExecutionAllowed = false;
    // Start the simulation task
    ((ExecutorService) getExecutor()).submit(() -> startVehicleSimulation());
  }

  @Override
  public void processMessage(Object message) {
    // Process speed multiplier message which might pause the vehicle.
    if (message instanceof SetSpeedMultiplier) {
      SetSpeedMultiplier lsMessage = (SetSpeedMultiplier) message;
      int multiplier = lsMessage.getMultiplier();
      getProcessModel().setVehiclePaused(multiplier == 0);
    }
  }

  @Override
  public synchronized void initVehiclePosition(String newPos) {
    ((ExecutorService) getExecutor()).submit(() -> getProcessModel().setVehiclePosition(newPos));
  }

  @Override
  public synchronized ExplainedBoolean canProcess(List<String> operations) {
    requireNonNull(operations, "operations");

    LOG.debug("{}: Checking processability of {}...", getName(), operations);
    boolean canProcess = true;
    String reason = "";

    // Do NOT require the vehicle to be IDLE or CHARGING here!
    // That would mean a vehicle moving to a parking position or recharging location would always
    // have to finish that order first, which would render a transport order's dispensable flag
    // useless.
    boolean loaded = loadState == LoadState.FULL;
    Iterator<String> opIter = operations.iterator();
    while (canProcess && opIter.hasNext()) {
      final String nextOp = opIter.next();
      // If we're loaded, we cannot load another piece, but could unload.
      if (loaded) {
        if (nextOp.startsWith(getProcessModel().getLoadOperation())) {
          canProcess = false;
          reason = LOAD_OPERATION_CONFLICT;
        }
        else if (nextOp.startsWith(getProcessModel().getUnloadOperation())) {
          loaded = false;
        }
      } // If we're not loaded, we could load, but not unload.
      else if (nextOp.startsWith(getProcessModel().getLoadOperation())) {
        loaded = true;
      }
      else if (nextOp.startsWith(getProcessModel().getUnloadOperation())) {
        canProcess = false;
        reason = UNLOAD_OPERATION_CONFLICT;
      }
    }
    if (!canProcess) {
      LOG.debug("{}: Cannot process {}, reason: '{}'", getName(), operations, reason);
    }
    return new ExplainedBoolean(canProcess, reason);
  }

  @Override
  protected synchronized boolean canSendNextCommand() {
    return super.canSendNextCommand()
        && (!getProcessModel().isSingleStepModeEnabled() || singleStepExecutionAllowed);
  }

  @Override
  protected synchronized void connectVehicle() {
  }

  @Override
  protected synchronized void disconnectVehicle() {
  }

  @Override
  protected synchronized boolean isVehicleConnected() {
    return true;
  }

  @Override
  protected VehicleProcessModelTO createCustomTransferableProcessModel() {
    return new LoopbackVehicleModelTO()
        .setLoadOperation(getProcessModel().getLoadOperation())
        .setMaxAcceleration(getProcessModel().getMaxAcceleration())
        .setMaxDeceleration(getProcessModel().getMaxDecceleration())
        .setMaxFwdVelocity(getProcessModel().getMaxFwdVelocity())
        .setMaxRevVelocity(getProcessModel().getMaxRevVelocity())
        .setOperatingTime(getProcessModel().getOperatingTime())
        .setSingleStepModeEnabled(getProcessModel().isSingleStepModeEnabled())
        .setUnloadOperation(getProcessModel().getUnloadOperation())
        .setVehiclePaused(getProcessModel().isVehiclePaused());
  }

  /**
   * Triggers a step in single step mode.
   */
  public synchronized void trigger() {
    singleStepExecutionAllowed = true;
  }

  private void startVehicleSimulation() {
    LOG.debug("Starting vehicle simulation...");
    Step step = getSentQueue().peek().getStep();
    if (step.getPath() == null) {
      return;
    }

    long pathLength = step.getPath().getLength();
    int maxVelocity;
    switch (step.getVehicleOrientation()) {
      case BACKWARD:
        maxVelocity = step.getPath().getMaxReverseVelocity();
        break;
      default:
        maxVelocity = step.getPath().getMaxVelocity();
        break;
    }
    String pointName = step.getDestinationPoint().getName();

    getProcessModel().setVehicleState(Vehicle.State.EXECUTING);
    getProcessModel().getVelocityController().addWayEntry(
        new WayEntry(pathLength, maxVelocity, pointName, step.getVehicleOrientation())
    );

    operationSimulationTimePassed = 0;
    ((ScheduledExecutorService) getExecutor()).schedule(() -> movementSimulation(),
                                                        getSimulationTimeStep(),
                                                        TimeUnit.MILLISECONDS);
  }

  private void movementSimulation() {
    if (!getProcessModel().getVelocityController().hasWayEntries()) {
      return;
    }

    WayEntry prevWayEntry = getProcessModel().getVelocityController().getCurrentWayEntry();
    getProcessModel().getVelocityController().advanceTime(getSimulationTimeStep());
    WayEntry currentWayEntry = getProcessModel().getVelocityController().getCurrentWayEntry();
    //if we are still on the same way entry then reschedule to do it again
    if (prevWayEntry == currentWayEntry) {
      ((ScheduledExecutorService) getExecutor()).schedule(() -> movementSimulation(),
                                                          getSimulationTimeStep(),
                                                          TimeUnit.MILLISECONDS);
    }
    else {
      //if the way enties are different then we have finished this step
      //and we can move on.
      getProcessModel().setVehiclePosition(prevWayEntry.getDestPointName());
      LOG.debug("Movement simulation finished.");
      if (!getSentQueue().peek().isWithoutOperation()) {
        LOG.debug("Now simulating vehicle operation...");
        ((ScheduledExecutorService) getExecutor()).schedule(() -> operationSimulation(),
                                                            getSimulationTimeStep(),
                                                            TimeUnit.MILLISECONDS);
      }
      else {
        finishVehicleSimulation();
      }
    }

  }

  private void operationSimulation() {
    operationSimulationTimePassed += getSimulationTimeStep();

    if (operationSimulationTimePassed < getProcessModel().getOperatingTime()) {
      getProcessModel().getVelocityController().advanceTime(getSimulationTimeStep());
      ((ScheduledExecutorService) getExecutor()).schedule(() -> operationSimulation(),
                                                          getSimulationTimeStep(),
                                                          TimeUnit.MILLISECONDS);
    }
    else {
      LOG.debug("Operation simulation finished.");
      String operation = getSentQueue().peek().getOperation();
      if (operation.equals(getProcessModel().getLoadOperation())) {
        // Update load handling devices as defined by this operation
        getProcessModel().setVehicleLoadHandlingDevices(
            Arrays.asList(new LoadHandlingDevice(LHD_NAME, true))
        );
      }
      else if (operation.equals(getProcessModel().getUnloadOperation())) {
        getProcessModel().setVehicleLoadHandlingDevices(
            Arrays.asList(new LoadHandlingDevice(LHD_NAME, false))
        );
      }
      finishVehicleSimulation();
    }
  }

  private void finishVehicleSimulation() {
    //Set the vehicle state to idle
    if (getSentQueue().size() <= 1 && getCommandQueue().isEmpty()) {
      getProcessModel().setVehicleState(Vehicle.State.IDLE);
    }
    // Let the comm adapter know we have finished this command.
    getProcessModel().commandExecuted(getSentQueue().poll());
  }

  private int getSimulationTimeStep() {
    return (int) (ADVANCE_TIME * configuration.simulationTimeFactor());
  }

  /**
   * The vehicle's possible load states.
   */
  private enum LoadState {
    EMPTY,
    FULL;
  }
}
