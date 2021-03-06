package org.volity.javolin.game;

import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.net.*;
import java.util.*;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smack.XMPPException;
import org.jivesoftware.smack.packet.IQ;
import org.jivesoftware.smack.packet.XMPPError;
import org.jivesoftware.smackx.Form;
import org.jivesoftware.smackx.FormField;
import org.jivesoftware.smackx.packet.DiscoverInfo;
import org.volity.client.GameServer;
import org.volity.client.GameTable;
import org.volity.client.comm.DiscoBackground;
import org.volity.client.comm.RPCBackground;
import org.volity.client.data.GameUIInfo;
import org.volity.client.data.VersionNumber;
import org.volity.client.translate.TokenFailure;
import org.volity.jabber.JIDUtils;
import org.volity.javolin.ErrorWrapper;
import org.volity.javolin.JavolinApp;
import org.volity.javolin.Localize;


/**
 * The all-singing, all-dancing, fully-asynchronous TableWindow factory.
 *
 * This is a separate class because it's gotten so big and ugly. The
 * MakeTableWindow object is transient; it contains the state needed to create
 * the TableWindow. (That is, the state needed to clean up if the creation
 * process dies halfway through.)
 */
public class MakeTableWindow
{
    JavolinApp mApp;
    XMPPConnection mConnection;
    TableWindowCallback mCallback;
    Window mParentDialog;

    GameServer mParlor;
    GameTable mGameTable;
    String mParlorID;
    String mTableID;
    String mNickname;
    TableWindow mTableWindow;
    boolean mIsCreating;

    /**
     * Create a MakeTableWindow. This does no work; it just sets up the context
     * for you to use later.
     *
     * @param app The JavolinApp.
     * @param connection The Jabber connection.
     * @param parent The window to hang error dialog boxes off of. It's okay
     *    if this is null; it's okay if the window closes while (or before)
     *    the MakeTableWindow is working.
     */
    public MakeTableWindow(JavolinApp app, XMPPConnection connection, 
        Window parent) {
        mApp = app;
        mConnection = connection;
        mParentDialog = parent;

        if (parent != null) {
            parent.addWindowListener(
                new WindowAdapter() {
                    public void windowClosed(WindowEvent ev) {
                        mParentDialog = null;
                    }
                });
        }
    }

    /**
     * The callback interface for table creation and table joining. Exactly one
     * of the two methods will be called:
     *
     *   void succeed(TableWindow win);
     *   void fail();
     *
     * In the case of succeed, the window may be a newly-created TableWindow or
     * one that previously existed.
     *
     * Whichever method is called will be called in the Swing UI thread.
     * Implementations of these methods must be fast -- if they block, the app
     * will lag.
     */
    public interface TableWindowCallback {
        public void succeed(TableWindow win);
        public void fail();
    }

    /** 
     * Begin work, creating a new table.
     *
     * @param serverID The JID of the game parlor. (If this does not have 
     *   "volity" as the JID resource, that is implicitly added.)
     * @param nickname The nickname which the player desires.
     * @param callback Callback to invoke when the process succeeds or fails.
     *   This may be null; if it is non-null, the methods must be fast (non-
     *   blocking).
     */
    public void newTable(
        String serverID,
        String nickname,
        TableWindowCallback callback) {

        build(serverID, null, nickname, callback);
    }

    /** 
     * Begin work, joining an existing table.
     *
     * @param tableID The JID of the game table MUC.
     * @param nickname The nickname which the player desires.
     * @param callback Callback to invoke when the process succeeds or fails.
     *   This may be null; if it is non-null, the methods must be fast (non-
     *   blocking).
     */
    public void joinTable(
        String tableID,
        String nickname,
        TableWindowCallback callback) {
        build(null, tableID, nickname, callback);
    }

    /**
     * Beginning of the internal sequence which handles both newTable() and
     * joinTable().
     */
    private void build(
        String serverID,
        String tableID,
        String nickname,
        TableWindowCallback callback) {

        mCallback = callback;
        mNickname = nickname;
        mGameTable = null;
        mParlor = null;
        mTableID = tableID;
        mIsCreating = false;

        if (mNickname == null)
            throw new RuntimeException("MakeTableWindow requires a nickname.");

        if (tableID == null && serverID == null)
            throw new RuntimeException("MakeTableWindow requires either serverID or tableID.");

        if (tableID == null) {
            // Create a new table.

            mIsCreating = true;
            mParlorID = serverID;

            if (!JIDUtils.hasResource(mParlorID)) {
                mParlorID = JIDUtils.setResource(mParlorID, "volity");
            }

            try {
                mParlor = new GameServer(mConnection, mParlorID);
                mParlor.newTable(new RPCBackground.Callback() {
                        public void run(Object result, Exception err, Object rock) {
                            GameTable table = (GameTable)result;
                            contCreateDidNewTable(table, err);
                        }
                    }, null);
            }
            catch (Exception ex) {
                contCreateDidNewTable(null, ex);
            }
            return;
        }

        // Join an existing table.

        // Stage 0: check to see if we're already connected to a MUC of this
        // name. If so, we're done.

        for (Iterator it = mApp.getTableWindows(); it.hasNext(); ) {
            TableWindow win = (TableWindow)it.next();
            if (mTableID.equals(win.getRoom())) {
                // We are. Bring up the existing window, and exit.
                callbackAlreadyConnected(win);
                win.setVisible(true);
                return;
            }
        }


        // Stage 1: check to see if the MUC exists.

        new DiscoBackground(mConnection, 
            new DiscoBackground.Callback() {
                public void run(IQ result, XMPPException err, Object rock) {
                    contJoinDidQueryTable(result, err);
                }
            },
            DiscoBackground.QUERY_INFO, mTableID, null);
    }

    private void contJoinDidQueryTable(IQ result, XMPPException err) {
        assert (SwingUtilities.isEventDispatchThread()) : "not in UI thread";

        if (err != null) {
            // Disco query failed.
            XMPPException ex = err;
            new ErrorWrapper(ex);
            callbackFail();

            String msg = localize("ErrorCouldNotContact");

            // Any or all of these may be null.
            String submsg = ex.getMessage();
            XMPPError error = ex.getXMPPError();
            Throwable subex = ex.getWrappedThrowable();

            if (error != null 
                && (error.getCode() == 404 || error.getCode() == 400)) {
                /* A common case: the JID was not found. */
                msg = localize("ErrorNoSuchTable");
                if (error.getMessage() != null)
                    msg = msg + " (" + error.getMessage() + ")";
                msg = msg + "\n(" + mTableID + ")";
            }
            else {
                if (submsg != null && subex == null && error == null)
                    msg = localize("ErrorCouldNotContactColon") + " " + submsg;
                else
                    msg = localize("ErrorCouldNotContact");
                if (subex != null)
                    msg = msg + "\n" + subex.toString();
                if (error != null)
                    msg = msg + "\nJabber error " + error.toString();
            }

            JOptionPane.showMessageDialog(mParentDialog, 
                msg,
                JavolinApp.getAppName() + ": Error", 
                JOptionPane.ERROR_MESSAGE);
            return;
        }

        assert (result != null && result instanceof DiscoverInfo);

        DiscoverInfo info = (DiscoverInfo)result;
        if (!info.containsFeature("http://jabber.org/protocol/muc")) {
            callbackFail();

            String msg = localize("ErrorNotATable", mTableID);

            JOptionPane.showMessageDialog(mParentDialog, 
                msg,
                JavolinApp.getAppName() + ": Error", 
                JOptionPane.ERROR_MESSAGE);
            return;
        }

        /* Disco success. Next step: Create a GameTable, and join the MUC.
         *
         * Note that we don't have a GameServer.
         */
        GameTable.ReadyListener listener = null;

        try
        {
            mGameTable = new GameTable(mConnection, mTableID);

            /* To get the GameServer, we need to join the MUC early. */

            listener = new GameTable.ReadyListener() {
                    public void ready() {
                        // Called outside Swing thread!
                        // Remove the listener, now that it's triggered
                        mGameTable.removeReadyListener(this);
                        // Invoke into the Swing thread.
                        SwingUtilities.invokeLater(new Runnable() {
                                public void run() {
                                    contJoinDoQueryRef();
                                }
                            });
                    }
                };
            mGameTable.addReadyListener(listener);

            mGameTable.join(mNickname);

            /*
             * Now we wait for the ReadyListener to fire, which will invoke
             * contJoinDoQueryRef(), below. 
             */
        }
        catch (XMPPException ex) 
        {
            new ErrorWrapper(ex);
            callbackFail();

            if (mGameTable != null) {
                if (listener != null)
                    mGameTable.removeReadyListener(listener);
                mGameTable.leave();
            }

            String msg = "The table could not be joined.";

            // Any or all of these may be null.
            String submsg = ex.getMessage();
            XMPPError error = ex.getXMPPError();
            Throwable subex = ex.getWrappedThrowable();

            if (error != null && error.getCode() == 404) 
            {
                /* A common case: the JID was not found. */
                msg = localize("ErrorNoSuchTable");
                if (error.getMessage() != null)
                    msg = msg + " (" + error.getMessage() + ")";
                msg = msg + "\n(" + mTableID + ")";
            }
            else if (error != null && error.getCode() == 409) 
            {
                /* A common case: your nickname conflicts. */
                msg = localize("ErrorNicknameConflict", mNickname);
            }
            else {
                if (submsg != null && subex == null && error == null)
                    msg = localize("ErrorCouldNotJoinTableColon") + " " + submsg;
                else
                    msg = localize("ErrorCouldNotJoinTable");
                if (subex != null)
                    msg = msg + "\n" + subex.toString();
                if (error != null)
                    msg = msg + "\nJabber error " + error.toString();
            }

            JOptionPane.showMessageDialog(mParentDialog, 
                msg,
                JavolinApp.getAppName() + ": Error", 
                JOptionPane.ERROR_MESSAGE);
        }
        catch (Exception ex)
        {
            new ErrorWrapper(ex);
            callbackFail();

            if (mGameTable != null) {
                if (listener != null)
                    mGameTable.removeReadyListener(listener);
                mGameTable.leave();
            }

            JOptionPane.showMessageDialog(mParentDialog, 
                localize("ErrorCouldNotJoinTableColon") + "\n" + ex.toString(),
                JavolinApp.getAppName() + ": Error", 
                JOptionPane.ERROR_MESSAGE);
        }
    }

    private void contJoinDoQueryRef()
    {
        assert (SwingUtilities.isEventDispatchThread()) : "not in UI thread";

        // Next step: disco the referee.

        String refJID = mGameTable.getRefereeJID();
        
        new DiscoBackground(mConnection, 
            new DiscoBackground.Callback() {
                public void run(IQ result, XMPPException err, Object rock) {
                    contJoinDidQueryRef(result, err);
                }
            },
            DiscoBackground.QUERY_INFO, refJID, null);
    }

    private void contJoinDidQueryRef(IQ result, XMPPException err) {
        assert (SwingUtilities.isEventDispatchThread()) : "not in UI thread";

        if (err != null) {
            // Disco query failed.
            XMPPException ex = err;
            new ErrorWrapper(ex);
            callbackFail();

            mGameTable.leave();

            JOptionPane.showMessageDialog(mParentDialog,
                localize("ErrorCannotContactReferee") + "\n" + ex.toString(),
                JavolinApp.getAppName() + ": Error",
                JOptionPane.ERROR_MESSAGE);
            return;
        }

        assert (result != null && result instanceof DiscoverInfo);

        try {
            DiscoverInfo info = (DiscoverInfo)result;
            mParlorID = null;

            Form form = Form.getFormFrom(info);
            if (form != null) {
                FormField field = form.getField("parlor");
                if (field != null)
                    mParlorID = (String) field.getValues().next();
            }
            
            if (mParlorID == null || mParlorID.equals("")) {
                throw new Exception("Unable to fetch parlor ID from referee");
            }

            // Next step: connect to the server, and construct the TableWindow.

            mParlor = new GameServer(mConnection, mParlorID);

            SwingUtilities.invokeLater(new Runnable() {
                    public void run() {
                        contJoinDoConstruct();
                    }
                });
            return;
        }
        catch (Exception ex)
        {
            new ErrorWrapper(ex);
            callbackFail();

            mGameTable.leave();

            JOptionPane.showMessageDialog(mParentDialog,
                localize("ErrorCouldNotJoinTableColon") + "\n" + ex.toString(),
                JavolinApp.getAppName() + ": Error",
                JOptionPane.ERROR_MESSAGE);
        }
    }

    private void contCreateDidNewTable(GameTable result, Exception err) {
        assert (SwingUtilities.isEventDispatchThread()) : "not in UI thread";

        if (err != null) {
            // RPC failed.
            new ErrorWrapper(err);
            callbackFail();

            if (err instanceof TokenFailure) {
                TokenFailure ex = (TokenFailure)err;

                String msg = JavolinApp.getTranslator().translate(ex);

                JOptionPane.showMessageDialog(mParentDialog,
                    localize("ErrorCouldNotCreateTableColon") + "\n" + msg,
                    JavolinApp.getAppName() + ": Error",
                    JOptionPane.ERROR_MESSAGE);
            }
            else if (err instanceof XMPPException) {
                XMPPException ex = (XMPPException)err;
                String msg = localize("ErrorCouldNotCreateTable");

                // Any or all of these may be null.
                String submsg = ex.getMessage();
                XMPPError error = ex.getXMPPError();
                Throwable subex = ex.getWrappedThrowable();

                if (error != null && error.getCode() == 404) {
                    /* A common case: the JID was not found. */
                    msg = localize("ErrorNoSuchParlor");
                    if (error.getMessage() != null)
                        msg = msg + " (" + error.getMessage() + ")";
                    msg = msg + "\n(" + mParlorID + ")";
                }
                else if (error != null && error.getCode() == 409) {
                    /* A common case: your nickname conflicts. */
                    msg = localize("ErrorNicknameConflict", mNickname);
                }
                else {
                    if (submsg != null && subex == null && error == null)
                        msg = localize("ErrorCouldNotCreateTableColon")
                            + " " + submsg;
                    else
                        msg = localize("ErrorCouldNotCreateTable");
                    if (subex != null)
                        msg = msg + "\n" + subex.toString();
                    if (error != null)
                        msg = msg + "\nJabber error " + error.toString();
                }

                JOptionPane.showMessageDialog(mParentDialog, 
                    msg,
                    JavolinApp.getAppName() + ": Error", 
                    JOptionPane.ERROR_MESSAGE);
            }
            else {
                Exception ex = err;
                JOptionPane.showMessageDialog(mParentDialog, 
                    localize("ErrorCouldNotCreateTableColon")
                    + "\n" + ex.toString(),
                    JavolinApp.getAppName() + ": Error",
                    JOptionPane.ERROR_MESSAGE);
            }

            return;
        }

        assert (result != null);
        mGameTable = result;

        contJoinDoConstruct();
    }

    /**
     * The new-table and join-table paths merge here. At this point, we have
     * both a GameServer object and a GameTable object. It is time to select a
     * UI.
     */
    private void contJoinDoConstruct() {
        assert (SwingUtilities.isEventDispatchThread()) : "not in UI thread";
        assert (mParlor != null);
        assert (mGameTable != null);
        assert (mTableWindow == null);

        SelectUI select; 
        try {
            URI ruleset = mParlor.getRuleset();
            if (ruleset == null)
                throw new NullPointerException();
            select = new SelectUI(ruleset, false);
        }
        catch (NullPointerException ex) {
            callbackFail();
            mGameTable.leave();
            JOptionPane.showMessageDialog(mParentDialog, 
                localize("ErrorNoRuleset"),
                JavolinApp.getAppName() + ": Error",
                JOptionPane.ERROR_MESSAGE);
            return;
        }
        catch (URISyntaxException ex) {
            callbackFail();
            mGameTable.leave();
            JOptionPane.showMessageDialog(mParentDialog, 
                localize("ErrorRulesetInvalid") + "\n" + ex.toString(),
                JavolinApp.getAppName() + ": Error",
                JOptionPane.ERROR_MESSAGE);
            return;
        }
        catch (VersionNumber.VersionFormatException ex) {
            callbackFail();
            mGameTable.leave();
            JOptionPane.showMessageDialog(mParentDialog, 
                localize("ErrorRulesetVersionInvalid") + "\n" + ex.toString(),
                JavolinApp.getAppName() + ": Error",
                JOptionPane.ERROR_MESSAGE);
            return;
        }


        select.select(new SelectUI.Callback() {
                public void succeed(URL ui, File localui) {
                    contJoinPickedUI(ui, localui);
                }
                public void fail() {
                    callbackFail();
                    mGameTable.leave();
                    JOptionPane.showMessageDialog(mParentDialog, 
                        localize("ErrorCannotSelectUI"),
                        JavolinApp.getAppName() + ": Error",
                        JOptionPane.ERROR_MESSAGE);
                }
            });
    }

    /**
     * Now we have a valid UI.
     */
    private void contJoinPickedUI(URL ui, File localui) {
        try {
            /* Once we call the TableWindow constructor, it owns the GameTable.
             * The constructor will clean up the GameTable on failure. */
            mTableWindow = new TableWindow(mParlor, mGameTable,
                mNickname, localui, ui);

            // If there were a failure after this point, we'd have to call
            // mTableWindow.leave(). But we're done.

            callbackSucceed();
            mApp.handleNewTableWindow(mTableWindow);
        }
        catch (TokenFailure ex)
        {
            callbackFail();

            mGameTable.leave();

            String msg = JavolinApp.getTranslator().translate(ex);

            if (mIsCreating)
                msg = localize("ErrorCouldNotCreateTableColon") + "\n" + msg;
            else
                msg = localize("ErrorCouldNotJoinTableColon") + "\n" + msg;

            JOptionPane.showMessageDialog(mParentDialog,
                msg,
                JavolinApp.getAppName() + ": Error",
                JOptionPane.ERROR_MESSAGE);
        }
        catch (XMPPException ex) 
        {
            new ErrorWrapper(ex);
            callbackFail();

            mGameTable.leave();

            String msg;
            if (mIsCreating)
                msg = localize("ErrorCouldNotCreateTable");
            else
                msg = localize("ErrorCouldNotJoinTable");

            // Any or all of these may be null.
            String submsg = ex.getMessage();
            XMPPError error = ex.getXMPPError();
            Throwable subex = ex.getWrappedThrowable();

            if (error != null && error.getCode() == 404) {
                /* A common case: the JID was not found. */
                msg = localize("ErrorNoSuchParlor");
                if (error.getMessage() != null)
                    msg = msg + " (" + error.getMessage() + ")";
                msg = msg + "\n(" + mParlorID + ")";
            }
            else if (error != null && error.getCode() == 409) {
                /* A common case: your nickname conflicts. */
                msg = localize("ErrorNicknameConflict", mNickname);
            }
            else {
                if (submsg != null && subex == null && error == null) {
                    if (mIsCreating)
                        msg = localize("ErrorCouldNotCreateTableColon");
                    else
                        msg = localize("ErrorCouldNotJoinTableColon");
                    msg = msg + " " + submsg;
                }
                else {
                    if (mIsCreating)
                        msg = localize("ErrorCouldNotCreateTable");
                    else
                        msg = localize("ErrorCouldNotJoinTable");
                }
                if (subex != null)
                    msg = msg + "\n" + subex.toString();
                if (error != null)
                    msg = msg + "\nJabber error " + error.toString();
            }

            JOptionPane.showMessageDialog(mParentDialog, 
                msg,
                JavolinApp.getAppName() + ": Error", 
                JOptionPane.ERROR_MESSAGE);
        }
        catch (Exception ex)
        {
            new ErrorWrapper(ex);
            callbackFail();

            mGameTable.leave();

            String msg;
            if (mIsCreating)
                msg = localize("ErrorCouldNotJoinTableColon");
            else
                msg = localize("ErrorCouldNotCreateTableColon");

            JOptionPane.showMessageDialog(mParentDialog, 
                msg + "\n" + ex.toString(),
                JavolinApp.getAppName() + ": Error",
                JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * Localization helper.
     */
    protected String localize(String key) {
        return Localize.localize("MakeTableWindow", key);
    }
    protected String localize(String key, Object arg1) {
        return Localize.localize("MakeTableWindow", key, arg1);
    }

    private void callbackFail() {
        assert (SwingUtilities.isEventDispatchThread()) : "not in UI thread";
        if (mCallback != null)
            mCallback.fail();
    }

    private void callbackAlreadyConnected(TableWindow win) {
        assert (SwingUtilities.isEventDispatchThread()) : "not in UI thread";
        assert (mTableWindow == null);
        assert (win != null);
        if (mCallback != null)
            mCallback.succeed(win);
    }

    private void callbackSucceed() {
        assert (SwingUtilities.isEventDispatchThread()) : "not in UI thread";
        assert (mTableWindow != null);
        if (mCallback != null)
            mCallback.succeed(mTableWindow);
    }
}
