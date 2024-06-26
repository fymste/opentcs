/**
 * Copyright (c) The openTCS Authors.
 *
 * This program is free software and subject to the MIT license. (For details,
 * see the licensing information (LICENSE.txt) you should have received with
 * this copy of the software.)
 */
package org.opentcs.access.rmi.factories;

import jakarta.annotation.Nullable;
import java.rmi.server.RMIClientSocketFactory;
import java.rmi.server.RMIServerSocketFactory;

/**
 * A provider for instances of {@link RMIClientSocketFactory} and {@link RMIServerSocketFactory}.
 * Generally one provider should provide compatible factories for clients and servers.
 */
public interface SocketFactoryProvider {

  /**
   * Returns a {@link RMIClientSocketFactory}.
   *
   * @return A {@link RMIClientSocketFactory}.
   * May be <code>null</code> to indicate that a default factory implementation is to be used.
   */
  @Nullable
  RMIClientSocketFactory getClientSocketFactory();

  /**
   * Returns a {@link RMIServerSocketFactory}.
   *
   * @return A {@link RMIServerSocketFactory}.
   * May be <code>null</code> to indicate that a default factory implementation is to be used.
   */
  @Nullable
  RMIServerSocketFactory getServerSocketFactory();
}
