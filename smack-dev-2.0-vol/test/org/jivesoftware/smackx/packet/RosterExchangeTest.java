/*
 * Created on 01/08/2003
 *
 */
package org.jivesoftware.smackx.packet;

import java.util.Iterator;

import org.jivesoftware.smack.*;
import org.jivesoftware.smack.filter.*;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smack.test.SmackTestCase;
import org.jivesoftware.smackx.*;

/**
 *
 * Test the Roster Exchange extension using the low level API
 *
 * @author Gaston Dombiak
 */
public class RosterExchangeTest extends SmackTestCase {

    /**
     * Constructor for RosterExchangeTest.
     * @param arg0
     */
    public RosterExchangeTest(String arg0) {
        super(arg0);
    }

    /**
     * Low level API test.
     * This is a simple test to use with a XMPP client and check if the client receives the message
     * 1. User_1 will send his/her roster entries to user_2
     */
    public void testSendRosterEntries() {
        // Create a chat for each connection
        Chat chat1 = getConnection(0).createChat(getBareJID(1));

        // Create the message to send with the roster
        Message msg = chat1.createMessage();
        msg.setSubject("Any subject you want");
        msg.setBody("This message contains roster items.");
        // Create a RosterExchange Package and add it to the message
        assertTrue("Roster has no entries", getConnection(0).getRoster().getEntryCount() > 0);
        RosterExchange rosterExchange = new RosterExchange(getConnection(0).getRoster());
        msg.addExtension(rosterExchange);

        // Send the message that contains the roster
        try {
            chat1.sendMessage(msg);
        } catch (Exception e) {
            fail("An error occured sending the message with the roster");
        }
    }

    /**
    * Low level API test.
    * 1. User_1 will send his/her roster entries to user_2
    * 2. User_2 will receive the entries and iterate over them to check if everything is fine
    * 3. User_1 will wait several seconds for an ACK from user_2, if none is received then something is wrong
    */
    public void testSendAndReceiveRosterEntries() {
        // Create a chat for each connection
        Chat chat1 = getConnection(0).createChat(getBareJID(1));
        final Chat chat2 = new Chat(getConnection(1), getBareJID(0), chat1.getThreadID());

        // Create a Listener that listens for Messages with the extension "jabber:x:roster"
        // This listener will listen on the conn2 and answer an ACK if everything is ok
        PacketFilter packetFilter = new PacketExtensionFilter("x", "jabber:x:roster");
        PacketListener packetListener = new PacketListener() {
            public void processPacket(Packet packet) {
                Message message = (Message) packet;
                assertNotNull("Body is null", message.getBody());
                try {
                    RosterExchange rosterExchange =
                        (RosterExchange) message.getExtension("x", "jabber:x:roster");
                    assertNotNull("Message without extension \"jabber:x:roster\"", rosterExchange);
                    assertTrue(
                        "Roster without entries",
                        rosterExchange.getRosterEntries().hasNext());
                    for (Iterator it = rosterExchange.getRosterEntries(); it.hasNext();) {
                        RemoteRosterEntry remoteRosterEntry = (RemoteRosterEntry) it.next();
                    }
                } catch (ClassCastException e) {
                    fail("ClassCastException - Most probable cause is that smack providers is misconfigured");
                }
                try {
                    chat2.sendMessage("ok");
                } catch (Exception e) {
                    fail("An error occured sending ack " + e.getMessage());
                }
            }
        };
        getConnection(1).addPacketListener(packetListener, packetFilter);

        // Create the message to send with the roster
        Message msg = chat1.createMessage();
        msg.setSubject("Any subject you want");
        msg.setBody("This message contains roster items.");
        // Create a RosterExchange Package and add it to the message
        assertTrue("Roster has no entries", getConnection(0).getRoster().getEntryCount() > 0);
        RosterExchange rosterExchange = new RosterExchange(getConnection(0).getRoster());
        msg.addExtension(rosterExchange);

        // Send the message that contains the roster
        try {
            chat1.sendMessage(msg);
        } catch (Exception e) {
            fail("An error occured sending the message with the roster");
        }
        // Wait for 2 seconds for a reply
        msg = chat1.nextMessage(2000);
        assertNotNull("No reply received", msg);
    }

    /**
     * Low level API test.
     * 1. User_1 will send his/her roster entries to user_2
     * 2. User_2 will automatically add the entries that receives to his/her roster in the corresponding group
     * 3. User_1 will wait several seconds for an ACK from user_2, if none is received then something is wrong
     */
    public void testSendAndAcceptRosterEntries() {
        // Create a chat for each connection
        Chat chat1 = getConnection(0).createChat(getBareJID(1));
        final Chat chat2 = new Chat(getConnection(1), getBareJID(0), chat1.getThreadID());

        // Create a Listener that listens for Messages with the extension "jabber:x:roster"
        // This listener will listen on the conn2, save the roster entries and answer an ACK if everything is ok
        PacketFilter packetFilter = new PacketExtensionFilter("x", "jabber:x:roster");
        PacketListener packetListener = new PacketListener() {
            public void processPacket(Packet packet) {
                Message message = (Message) packet;
                assertNotNull("Body is null", message.getBody());
                try {
                    RosterExchange rosterExchange =
                        (RosterExchange) message.getExtension("x", "jabber:x:roster");
                    assertNotNull("Message without extension \"jabber:x:roster\"", rosterExchange);
                    assertTrue(
                        "Roster without entries",
                        rosterExchange.getRosterEntries().hasNext());
                    // Add the roster entries to user2's roster
                    for (Iterator it = rosterExchange.getRosterEntries(); it.hasNext();) {
                        RemoteRosterEntry remoteRosterEntry = (RemoteRosterEntry) it.next();
                        getConnection(1).getRoster().createEntry(
                            remoteRosterEntry.getUser(),
                            remoteRosterEntry.getName(),
                            remoteRosterEntry.getGroupArrayNames());
                    }
                } catch (ClassCastException e) {
                    fail("ClassCastException - Most probable cause is that smack providers is misconfigured");
                } catch (Exception e) {
                    fail(e.toString());
                }
                try {
                    chat2.sendMessage("ok");
                } catch (Exception e) {
                    fail("An error occured sending ack " + e.getMessage());
                }
            }
        };
        getConnection(1).addPacketListener(packetListener, packetFilter);

        // Create the message to send with the roster
        Message msg = chat1.createMessage();
        msg.setSubject("Any subject you want");
        msg.setBody("This message contains roster items.");
        // Create a RosterExchange Package and add it to the message
        assertTrue("Roster has no entries", getConnection(0).getRoster().getEntryCount() > 0);
        RosterExchange rosterExchange = new RosterExchange(getConnection(0).getRoster());
        msg.addExtension(rosterExchange);

        // Send the message that contains the roster
        try {
            chat1.sendMessage(msg);
        } catch (Exception e) {
            fail("An error occured sending the message with the roster");
        }
        // Wait for 10 seconds for a reply
        msg = chat1.nextMessage(5000);
        assertNotNull("No reply received", msg);
        try {
            Thread.sleep(200);
        } catch (Exception e) {
        }
        assertTrue("Roster2 has no entries", getConnection(1).getRoster().getEntryCount() > 0);
    }

    protected void setUp() throws Exception {
        super.setUp();
        try {
            getConnection(0).getRoster().createEntry(
                getBareJID(2),
                "gato5",
                new String[] { "Friends, Coworker" });
            getConnection(0).getRoster().createEntry(getBareJID(3), "gato6", null);
            Thread.sleep(300);

        } catch (Exception e) {
            fail(e.getMessage());
        }
    }

    protected int getMaxConnections() {
        return 4;
    }

}
