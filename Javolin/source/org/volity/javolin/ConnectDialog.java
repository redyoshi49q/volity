/*
 * ConnectDialog.java
 * Source code Copyright 2004 by Karl von Laudermann
 */
package org.volity.javolin;

import java.awt.*;
import java.awt.event.*;
import java.util.prefs.*;
import javax.swing.*;
import org.jivesoftware.smack.*;
import org.jivesoftware.smack.packet.*;

/**
 * The dialog for establishing a connection to a Jabber server
 *
 * @author   karlvonl
 */
public class ConnectDialog extends BaseDialog implements ActionListener
{
    private final static String NODENAME = "ConnectDialog";
    private final static String HOSTNAME_KEY = "HostName";
    private final static String USERNAME_KEY = "UserName";

    private JTextField mHostNameField;
    private JTextField mUserNameField;
    private JPasswordField mPasswordField;
    private JButton mCancelButton;
    private JButton mConnectButton;

    private XMPPConnection mConnection = null;

    /**
     * Constructor for the ConnectDialog object
     *
     * @param owner  Description of the Parameter
     */
    public ConnectDialog(Frame owner)
    {
        super(owner, JavolinApp.getAppName() + ": Connect", true, NODENAME);

        // Set up dialog
        buildUI();
        setResizable(false);
        pack();

        // Restore saved window position
        restoreSizeAndPosition();

        // Restore default field values
        restoreFieldValues();

        // Set focus to first blank field
        if (mHostNameField.getText().equals(""))
        {
            mHostNameField.requestFocusInWindow();
        }
        else if (mUserNameField.getText().equals(""))
        {
            mUserNameField.requestFocusInWindow();
        }
        else
        {
            mPasswordField.requestFocusInWindow();
        }
    }


    /**
     * Gets the XMPPConnection holding the connection that was established.
     *
     * @return   The XMPPConnection that was created when the user pressed the Connect
     * button, or null if the user pressed Cancel or if there was an error connecting.
     */
    public XMPPConnection getConnection()
    {
        return mConnection;
    }

    /**
     * ActionListener interface method implementation.
     *
     * @param e  The action event to handle.
     */
    public void actionPerformed(ActionEvent e)
    {
        if (e.getSource() == mConnectButton)
        {
            doConnect();
        }
        else if (e.getSource() == mCancelButton)
        {
            dispose();
        }
    }

    /**
     * Handles the Connect button
     */
    private void doConnect()
    {
        // Store field values in preferences
        saveFieldValues();

        // Connect
        try
        {
            mConnection = new XMPPConnection(mHostNameField.getText());
            mConnection.login(mUserNameField.getText(),
                new String(mPasswordField.getPassword()));
            dispose();
        }
        catch (XMPPException ex)
        {
            String message = ex.toString();
            XMPPError error = ex.getXMPPError();

            if (error != null)
            {
                switch (error.getCode())
                {
                case 502:
                    message = "Error 502: Could not connect to host " +
                        mHostNameField.getText() + ".";
                    break;
                case 401:
                    message = "Error 401: Unauthorized access to host " +
                        mHostNameField.getText() +
                        ".\nMake sure your user name and password are correct.";
                    break;
                }
            }

            JOptionPane.showMessageDialog(this, message,
                JavolinApp.getAppName() + ": Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * Saves the current text of the host name and user name fields to the preferences
     * storage
     */
    private void saveFieldValues()
    {
        Preferences prefs = Preferences.userNodeForPackage(getClass()).node(NODENAME);

        prefs.put(HOSTNAME_KEY, mHostNameField.getText());
        prefs.put(USERNAME_KEY, mUserNameField.getText());
    }

    /**
     * Reads the default host name and user name values from the preferences storage and
     * fills in the text fields
     */
    private void restoreFieldValues()
    {
        Preferences prefs = Preferences.userNodeForPackage(getClass()).node(NODENAME);

        mHostNameField.setText(prefs.get(HOSTNAME_KEY, "volity.net"));
        mUserNameField.setText(prefs.get(USERNAME_KEY, ""));
    }

    /**
     * Populates the dialog with controls. This method is called once, from the
     * constructor.
     */
    private void buildUI()
    {
        getContentPane().setLayout(new GridBagLayout());
        GridBagConstraints c;

        int gridY = 0;

        // Add host label
        JLabel someLabel = new JLabel("Host:");
        c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = gridY;
        c.insets = new Insets(MARGIN, MARGIN, 0, 0);
        c.anchor = GridBagConstraints.WEST;
        getContentPane().add(someLabel, c);

        // Add host field
        mHostNameField = new JTextField(15);
        c = new GridBagConstraints();
        c.gridx = 1;
        c.gridy = gridY;
        c.insets = new Insets(MARGIN, SPACING, 0, MARGIN);
        getContentPane().add(mHostNameField, c);
        gridY++;

        // Add user name label
        someLabel = new JLabel("User ID:");
        c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = gridY;
        c.insets = new Insets(SPACING, MARGIN, 0, 0);
        c.anchor = GridBagConstraints.WEST;
        getContentPane().add(someLabel, c);

        // Add user field
        mUserNameField = new JTextField(15);
        c = new GridBagConstraints();
        c.gridx = 1;
        c.gridy = gridY;
        c.insets = new Insets(SPACING, SPACING, 0, MARGIN);
        getContentPane().add(mUserNameField, c);
        gridY++;

        // Add password label
        someLabel = new JLabel("Password:");
        c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = gridY;
        c.insets = new Insets(SPACING, MARGIN, 0, 0);
        c.anchor = GridBagConstraints.WEST;
        getContentPane().add(someLabel, c);

        // Add password field
        mPasswordField = new JPasswordField(15);
        c = new GridBagConstraints();
        c.gridx = 1;
        c.gridy = gridY;
        c.insets = new Insets(SPACING, SPACING, 0, MARGIN);
        getContentPane().add(mPasswordField, c);
        gridY++;

        // Add panel with Cancel and Connect buttons
        JPanel buttonPanel = new JPanel(new GridBagLayout());
        c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = gridY;
        c.gridwidth = GridBagConstraints.REMAINDER;
        c.insets = new Insets(GAP, MARGIN, MARGIN, MARGIN);
        c.anchor = GridBagConstraints.EAST;
        c.weightx = 0.5;
        getContentPane().add(buttonPanel, c);
        gridY++;

        // Add Cancel button
        mCancelButton = new JButton("Cancel");
        mCancelButton.addActionListener(this);
        c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.insets = new Insets(0, 0, 0, 0);
        c.anchor = GridBagConstraints.EAST;
        c.weightx = 0.5;
        buttonPanel.add(mCancelButton, c);

        // Add Connect button
        mConnectButton = new JButton("Connect");
        mConnectButton.addActionListener(this);
        c = new GridBagConstraints();
        c.gridx = 1;
        c.gridy = 0;
        c.insets = new Insets(0, SPACING, 0, 0);
        c.anchor = GridBagConstraints.EAST;
        buttonPanel.add(mConnectButton, c);
        // Make Connect button default
        getRootPane().setDefaultButton(mConnectButton);

        // Make the Connect button and the Cancel button the same width
        mCancelButton.setPreferredSize(mConnectButton.getPreferredSize());
    }
}