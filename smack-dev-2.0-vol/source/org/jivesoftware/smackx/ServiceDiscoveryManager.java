/**
 * $RCSfile$
 * $Revision$
 * $Date$
 *
 * Copyright 2003-2004 Jive Software.
 *
 * All rights reserved. Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jivesoftware.smackx;

import java.util.*;

import org.jivesoftware.smack.*;
import org.jivesoftware.smack.filter.*;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smackx.packet.*;

/**
 * Manages discovery of services in XMPP entities. This class provides:
 * <ol>
 * <li>A registry of supported features in this XMPP entity.
 * <li>Automatic response when this XMPP entity is queried for information.
 * <li>Ability to discover items and information of remote XMPP entities.
 * <li>Ability to publish publicly available items.
 * </ol>  
 * 
 * @author Gaston Dombiak
 */
public class ServiceDiscoveryManager {

    protected static String identityName = "Smack";
    protected static String identityType = "pc";
    protected static Form identityExtForm = null;

    protected static ServiceDiscoveryFactory factory;
    protected static Map instances = new Hashtable();

    protected XMPPConnection connection;
    protected List features = new ArrayList();
    protected Map nodeInformationProviders = new Hashtable();

    // Create a new ServiceDiscoveryManager on every established connection
    static {
        factory = new DefaultServiceDiscoveryFactory();

        XMPPConnection.addConnectionListener(new ConnectionEstablishedListener() {
            public void connectionEstablished(XMPPConnection connection) {
                if (factory != null) {
                    factory.create(connection);
                }
            }
        });
    }

    /**
     * Set a factory to create ServiceDiscoveryManagers for all future
     * XMPPConnections.
     *
     * It is legal to set this to null. This indicates that you do not want to
     * automatically create SDMs for your connections.
     *
     * (This option -- setting the ServiceDiscoveryFactory to null -- is
     * provided only for cases where you plan to *manually* create a
     * ServiceDiscoveryManager instance for each XMPPConnection. If you leave a
     * connection with no SDM at all, various parts of Smack will not work
     * correctly.)
     *
     * For cases where you want a connection to not respond to service-
     * discovery requests, see the QuietServiceDiscoveryManager class.
     *
     * NOTE: If you call this -- or any smackx static method -- before
     * instantiating your first XMPPConnection, you may run into a class
     * loading race condition. This manifests as a NullPointerException in
     * XHTMLManager. To avoid this, call
     *     SmackConfiguration.getVersion()
     * before your first call to setServiceDiscoveryFactory().
     *
     * @param newFactory a new factory object. (May be null, but see above.)
     */
    public static void setServiceDiscoveryFactory(ServiceDiscoveryFactory newFactory) {
        factory = newFactory;
    }

    /**
     * Get the current ServiceDiscoveryFactory. The default factory
     * simply instantiates ServiceDiscoveryManager.
     *
     * @return the current ServiceDiscoveryFactory
     */
    public static ServiceDiscoveryFactory getServiceDiscoveryFactory() {
        return factory;
    }

    /**
     * Creates a new ServiceDiscoveryManager for a given XMPPConnection. This means that the 
     * service manager will respond to any service discovery request that the connection may
     * receive. 
     * 
     * @param connection the connection to which a ServiceDiscoveryManager is going to be created.
     */
    public ServiceDiscoveryManager(XMPPConnection connection) {
        this.connection = connection;
        init();
    }

    /**
     * Returns the ServiceDiscoveryManager instance associated with a given XMPPConnection.
     * 
     * @param connection the connection used to look for the proper ServiceDiscoveryManager.
     * @return the ServiceDiscoveryManager associated with a given XMPPConnection.
     */
    public static ServiceDiscoveryManager getInstanceFor(XMPPConnection connection) {
        return (ServiceDiscoveryManager) instances.get(connection);
    }

    /**
     * Returns the name of the client that will be returned when asked for the client identity
     * in a disco request. The name could be any value you need to identity this client.
     * 
     * @return the name of the client that will be returned when asked for the client identity
     *          in a disco request.
     */
    public static String getIdentityName() {
        return identityName;
    }

    /**
     * Sets the name of the client that will be returned when asked for the client identity
     * in a disco request. The name could be any value you need to identity this client.
     * 
     * @param name the name of the client that will be returned when asked for the client identity
     *          in a disco request.
     */
    public static void setIdentityName(String name) {
        identityName = name;
    }

    /**
     * Returns the type of client that will be returned when asked for the client identity in a 
     * disco request. The valid types are defined by the category client. Follow this link to learn 
     * the possible types: <a href="http://www.jabber.org/registrar/disco-categories.html#client">Jabber::Registrar</a>.
     * 
     * @return the type of client that will be returned when asked for the client identity in a 
     *          disco request.
     */
    public static String getIdentityType() {
        return identityType;
    }

    /**
     * Sets the type of client that will be returned when asked for the client identity in a 
     * disco request. The valid types are defined by the category client. Follow this link to learn 
     * the possible types: <a href="http://www.jabber.org/registrar/disco-categories.html#client">Jabber::Registrar</a>.
     * 
     * @param type the type of client that will be returned when asked for the client identity in a 
     *          disco request.
     */
    public static void setIdentityType(String type) {
        identityType = type;
    }

    /**
     * Get the extension form (JEP-0128) which will be returned with the client
     * identity. If this is null, no form will be returned.
     *
     * @return a form of type "result". May be null.
     */
    public static Form getIdentityExtension() {
        return identityExtForm;
    }

    /**
     * Set an extension form (JEP-0128) which will be returned with the client
     * identity. If this is null, no form will be returned.
     * 
     * @param form a form of type "result". May be null.
     */
    public static void setIdentityExtension(Form form) {
        identityExtForm = form;
    }

    /**
     * Add the instance to the static table, and set up the various listeners.
     */
    protected void init() {
        // Register the new instance and associate it with the connection 
        instances.put(connection, this);
        // Add a listener to the connection that removes the registered instance when
        // the connection is closed
        connection.addConnectionListener(new ConnectionListener() {
            public void connectionClosed() {
                // Unregister this instance since the connection has been closed
                instances.remove(connection);
            }

            public void connectionClosedOnError(Exception e) {
                // Unregister this instance since the connection has been closed
                instances.remove(connection);
            }
        });

        initPacketListener();
    }

    /**
     * Initializes the packet listeners of the connection that will answer to
     * any service discovery request.
     *
     * This is broken out into a separate method so that subclasses can
     * override it.
     */
    protected void initPacketListener() {
        // Listen for disco#items requests and answer with an empty result        
        PacketFilter packetFilter = new PacketTypeFilter(DiscoverItems.class);
        PacketListener packetListener = new PacketListener() {
            public void processPacket(Packet packet) {
                DiscoverItems discoverItems = (DiscoverItems) packet;
                // Send back the items defined in the client if the request is of type GET
                if (discoverItems != null && discoverItems.getType() == IQ.Type.GET) {
                    DiscoverItems response = new DiscoverItems();
                    response.setType(IQ.Type.RESULT);
                    response.setTo(discoverItems.getFrom());
                    response.setPacketID(discoverItems.getPacketID());

                    // Add the defined items related to the requested node. Look for 
                    // the NodeInformationProvider associated with the requested node.  
                    if (getNodeInformationProvider(discoverItems.getNode()) != null) {
                        Iterator items =
                            getNodeInformationProvider(discoverItems.getNode()).getNodeItems();
                        while (items.hasNext()) {
                            response.addItem((DiscoverItems.Item) items.next());
                        }
                    }
                    connection.sendPacket(response);
                }
            }
        };
        connection.addPacketListener(packetListener, packetFilter);

        // Listen for disco#info requests and answer the client's supported features 
        // To add a new feature as supported use the #addFeature message        
        packetFilter = new PacketTypeFilter(DiscoverInfo.class);
        packetListener = new PacketListener() {
            public void processPacket(Packet packet) {
                DiscoverInfo discoverInfo = (DiscoverInfo) packet;
                // Answer the client's supported features if the request is of the GET type
                if (discoverInfo != null && discoverInfo.getType() == IQ.Type.GET) {
                    DiscoverInfo response = new DiscoverInfo();
                    response.setType(IQ.Type.RESULT);
                    response.setTo(discoverInfo.getFrom());
                    response.setPacketID(discoverInfo.getPacketID());
                    // Add the client's identity and features only if "node" is null
                    if (discoverInfo.getNode() == null) {
                        // Set this client identity
                        DiscoverInfo.Identity identity = new DiscoverInfo.Identity("client",
                                getIdentityName());
                        identity.setType(getIdentityType());
                        response.addIdentity(identity);
                        // Add the registered features to the response
                        synchronized (features) {
                            for (Iterator it = getFeatures(); it.hasNext();) {
                                response.addFeature((String) it.next());
                            }
                        }
                        // Add the form, if any
                        if (identityExtForm != null) {
                            response.addExtension(identityExtForm.getDataFormToSend());
                        }
                    }
                    else {
                        // Return an <item-not-found/> error since a client doesn't have nodes
                        response.setNode(discoverInfo.getNode());
                        response.setType(IQ.Type.ERROR);
                        response.setError(new XMPPError(404, "item-not-found"));
                    }
                    connection.sendPacket(response);
                }
            }
        };
        connection.addPacketListener(packetListener, packetFilter);
    }

    /**
     * Returns the NodeInformationProvider responsible for providing information 
     * (ie items) related to a given node or <tt>null</null> if none.<p>
     * 
     * In MUC, a node could be 'http://jabber.org/protocol/muc#rooms' which means that the
     * NodeInformationProvider will provide information about the rooms where the user has joined.
     * 
     * @param node the node that contains items associated with an entity not addressable as a JID.
     * @return the NodeInformationProvider responsible for providing information related 
     * to a given node.
     */
    protected NodeInformationProvider getNodeInformationProvider(String node) {
        if (node == null) {
            return null;
        }
        return (NodeInformationProvider) nodeInformationProviders.get(node);
    }

    /**
     * Sets the NodeInformationProvider responsible for providing information 
     * (ie items) related to a given node. Every time this client receives a disco request
     * regarding the items of a given node, the provider associated to that node will be the 
     * responsible for providing the requested information.<p>
     * 
     * In MUC, a node could be 'http://jabber.org/protocol/muc#rooms' which means that the
     * NodeInformationProvider will provide information about the rooms where the user has joined. 
     * 
     * @param node the node whose items will be provided by the NodeInformationProvider.
     * @param listener the NodeInformationProvider responsible for providing items related
     *      to the node.
     */
    public void setNodeInformationProvider(String node, NodeInformationProvider listener) {
        nodeInformationProviders.put(node, listener);
    }

    /**
     * Removes the NodeInformationProvider responsible for providing information 
     * (ie items) related to a given node. This means that no more information will be
     * available for the specified node.
     * 
     * In MUC, a node could be 'http://jabber.org/protocol/muc#rooms' which means that the
     * NodeInformationProvider will provide information about the rooms where the user has joined. 
     * 
     * @param node the node to remove the associated NodeInformationProvider.
     */
    public void removeNodeInformationProvider(String node) {
        nodeInformationProviders.remove(node);
    }

    /**
     * Returns the supported features by this XMPP entity.
     * 
     * @return an Iterator on the supported features by this XMPP entity.
     */
    public Iterator getFeatures() {
        synchronized (features) {
            return Collections.unmodifiableList(new ArrayList(features)).iterator();
        }
    }

    /**
     * Registers that a new feature is supported by this XMPP entity. When this client is 
     * queried for its information the registered features will be answered.<p>
     *
     * Since no packet is actually sent to the server it is safe to perform this operation
     * before logging to the server. In fact, you may want to configure the supported features
     * before logging to the server so that the information is already available if it is required
     * upon login.
     *
     * @param feature the feature to register as supported.
     */
    public void addFeature(String feature) {
        synchronized (features) {
            features.add(feature);
        }
    }

    /**
     * Removes the specified feature from the supported features by this XMPP entity.<p>
     *
     * Since no packet is actually sent to the server it is safe to perform this operation
     * before logging to the server.
     *
     * @param feature the feature to remove from the supported features.
     */
    public void removeFeature(String feature) {
        synchronized (features) {
            features.remove(feature);
        }
    }

    /**
     * Returns true if the specified feature is registered in the ServiceDiscoveryManager.
     *
     * @param feature the feature to look for.
     * @return a boolean indicating if the specified featured is registered or not.
     */
    public boolean includesFeature(String feature) {
        synchronized (features) {
            return features.contains(feature);
        }
    }

    /**
     * Returns the discovered information of a given XMPP entity addressed by its JID.
     * 
     * @param entityID the address of the XMPP entity.
     * @return the discovered information.
     * @throws XMPPException if the operation failed for some reason.
     */
    public DiscoverInfo discoverInfo(String entityID) throws XMPPException {
        return discoverInfo(entityID, null);
    }

    /**
     * Returns the discovered information of a given XMPP entity addressed by its JID and
     * note attribute. Use this message only when trying to query information which is not 
     * directly addressable.
     * 
     * @param entityID the address of the XMPP entity.
     * @param node the attribute that supplements the 'jid' attribute.
     * @return the discovered information.
     * @throws XMPPException if the operation failed for some reason.
     */
    public DiscoverInfo discoverInfo(String entityID, String node) throws XMPPException {
        // Discover the entity's info
        DiscoverInfo disco = new DiscoverInfo();
        disco.setType(IQ.Type.GET);
        disco.setTo(entityID);
        disco.setNode(node);

        // Create a packet collector to listen for a response.
        PacketCollector collector =
            connection.createPacketCollector(new PacketIDFilter(disco.getPacketID()));

        connection.sendPacket(disco);

        // Wait up to 5 seconds for a result.
        IQ result = (IQ) collector.nextResult(SmackConfiguration.getPacketReplyTimeout());
        // Stop queuing results
        collector.cancel();
        if (result == null) {
            throw new XMPPException("No response from the server.");
        }
        if (result.getType() == IQ.Type.ERROR) {
            throw new XMPPException(result.getError());
        }
        return (DiscoverInfo) result;
    }

    /**
     * Returns the discovered items of a given XMPP entity addressed by its JID.
     * 
     * @param entityID the address of the XMPP entity.
     * @return the discovered information.
     * @throws XMPPException if the operation failed for some reason.
     */
    public DiscoverItems discoverItems(String entityID) throws XMPPException {
        return discoverItems(entityID, null);
    }

    /**
     * Returns the discovered items of a given XMPP entity addressed by its JID and
     * note attribute. Use this message only when trying to query information which is not 
     * directly addressable.
     * 
     * @param entityID the address of the XMPP entity.
     * @param node the attribute that supplements the 'jid' attribute.
     * @return the discovered items.
     * @throws XMPPException if the operation failed for some reason.
     */
    public DiscoverItems discoverItems(String entityID, String node) throws XMPPException {
        // Discover the entity's items
        DiscoverItems disco = new DiscoverItems();
        disco.setType(IQ.Type.GET);
        disco.setTo(entityID);
        disco.setNode(node);

        // Create a packet collector to listen for a response.
        PacketCollector collector =
            connection.createPacketCollector(new PacketIDFilter(disco.getPacketID()));

        connection.sendPacket(disco);

        // Wait up to 5 seconds for a result.
        IQ result = (IQ) collector.nextResult(SmackConfiguration.getPacketReplyTimeout());
        // Stop queuing results
        collector.cancel();
        if (result == null) {
            throw new XMPPException("No response from the server.");
        }
        if (result.getType() == IQ.Type.ERROR) {
            throw new XMPPException(result.getError());
        }
        return (DiscoverItems) result;
    }

    /**
     * Returns true if the server supports publishing of items. A client may wish to publish items
     * to the server so that the server can provide items associated to the client. These items will
     * be returned by the server whenever the server receives a disco request targeted to the bare
     * address of the client (i.e. user@host.com).
     * 
     * @param entityID the address of the XMPP entity.
     * @return true if the server supports publishing of items.
     * @throws XMPPException if the operation failed for some reason.
     */
    public boolean canPublishItems(String entityID) throws XMPPException {
        DiscoverInfo info = discoverInfo(entityID);
        return info.containsFeature("http://jabber.org/protocol/disco#publish");
    }

    /**
     * Publishes new items to a parent entity. The item elements to publish MUST have at least 
     * a 'jid' attribute specifying the Entity ID of the item, and an action attribute which 
     * specifies the action being taken for that item. Possible action values are: "update" and 
     * "remove".
     * 
     * @param entityID the address of the XMPP entity.
     * @param discoverItems the DiscoveryItems to publish.
     * @throws XMPPException if the operation failed for some reason.
     */
    public void publishItems(String entityID, DiscoverItems discoverItems)
            throws XMPPException {
        publishItems(entityID, null, discoverItems);
    }

    /**
     * Publishes new items to a parent entity and node. The item elements to publish MUST have at 
     * least a 'jid' attribute specifying the Entity ID of the item, and an action attribute which 
     * specifies the action being taken for that item. Possible action values are: "update" and 
     * "remove".
     * 
     * @param entityID the address of the XMPP entity.
     * @param node the attribute that supplements the 'jid' attribute.
     * @param discoverItems the DiscoveryItems to publish.
     * @throws XMPPException if the operation failed for some reason.
     */
    public void publishItems(String entityID, String node, DiscoverItems discoverItems)
            throws XMPPException {
        discoverItems.setType(IQ.Type.SET);
        discoverItems.setTo(entityID);
        discoverItems.setNode(node);

        // Create a packet collector to listen for a response.
        PacketCollector collector =
            connection.createPacketCollector(new PacketIDFilter(discoverItems.getPacketID()));

        connection.sendPacket(discoverItems);

        // Wait up to 5 seconds for a result.
        IQ result = (IQ) collector.nextResult(SmackConfiguration.getPacketReplyTimeout());
        // Stop queuing results
        collector.cancel();
        if (result == null) {
            throw new XMPPException("No response from the server.");
        }
        if (result.getType() == IQ.Type.ERROR) {
            throw new XMPPException(result.getError());
        }
    }
}
