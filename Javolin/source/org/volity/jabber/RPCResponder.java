package org.volity.jabber;

import org.jivesoftware.smack.*;
import org.jivesoftware.smack.filter.*;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smack.provider.ProviderManager;
import org.volity.jabber.packet.*;
import org.volity.jabber.provider.*;
import java.util.*;

/**
 * A class for responding to Jabber-RPC requests.
 *
 * @author Doug Orleans (dougo@place.org)
 */
public class RPCResponder implements PacketListener {
  static {
    // Register the provider so that request packets get parsed correctly.
    ProviderManager.addIQProvider("query", "jabber:iq:rpc", new RPCProvider());
  }

  /**
   * @param connection a connection to an XMPP server
   * @param filter a filter specifying which RPC requests to handle
   * @param handler a handler for RPC requests
   */
  public RPCResponder(XMPPConnection connection,
                      PacketFilter filter,
                      RPCHandler handler) {
    this.connection = connection;
    this.filter = filter;
    this.handler = handler;
  }

  protected XMPPConnection connection;
  public XMPPConnection getConnection() { return connection; }

  protected PacketFilter filter;
  public PacketFilter getFilter() { return filter; }

  protected RPCHandler handler;
  public RPCHandler getHandler() { return handler; }

  /**
   * Start listening for requests.
   */
  public void start() {
    PacketFilter filter = new PacketTypeFilter(RPCRequest.class);
    if (this.filter != null) filter = new AndFilter(filter, this.filter);
    connection.addPacketListener(this, filter);
  }

  /**
   * Stop listening for requests.
   */
  public void stop() {
    connection.removePacketListener(this);
  }

  // Inherited from PacketListener.
  public void processPacket(Packet packet) {

    // This redundant check against the filter might be necessary
    // because org.jivesoftware.smack.PacketReader runs the filters in
    // a different thread from the listeners (!) and a filter might
    // depend on previous packets having been processed.  A future
    // version of Smack may fix this, in which case this check should
    // be removed to avoid running the filter twice.
    if (filter != null && !filter.accept(packet)) return;

    final RPCRequest req = (RPCRequest) packet;
    // Look, ma, continuation-passing style!
    RPCResponseHandler k = new RPCResponseHandler() {
        public void respondValue(Object value) {
          respond(new RPCResult(value));
        }
        public void respondFault(int faultCode, String faultString) {
          respond(new RPCFault(faultCode, faultString));
        }
        void respond(RPCResponse resp) {
          resp.setTo(req.getFrom());
          String id = req.getPacketID();
          if (id != null)
            resp.setPacketID(id);
          connection.sendPacket(resp);
        }
      };
    handler.handleRPC(req.getMethodName(), req.getParams(), k);
  }
}
