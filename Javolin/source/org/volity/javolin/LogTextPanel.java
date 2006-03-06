/*
 * LogTextPanel.java
 *
 * Copyright 2004 Karl von Laudermann
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.volity.javolin;

import java.awt.*;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.text.*;

/**
 * A read-only, vertically-scrolling text pane, which text can only be appended
 * to the end of, and which automatically scrolls to the bottom each time text
 * is appended.
 *
 * See also ChatLogPanel, which is a subclass of this intended for chat
 * interaction.
 */
public class LogTextPanel extends JPanel implements ChangeListener
{
    protected JTextPane mLogTextPane;
    protected JScrollPane mLogScroller;

    protected SimpleAttributeSet mBaseAttrs;
    protected boolean mShouldScroll;

    /**
     * Constructor.
     */
    public LogTextPanel()
    {
        mBaseAttrs = new SimpleAttributeSet();
        StyleConstants.setFontFamily(mBaseAttrs, "SansSerif");
        StyleConstants.setFontSize(mBaseAttrs, 12);

        buildUI();
    }

    /** Clean up component. */
    public void dispose() {
    }

    /**
     * Appends the given text to the text pane.
     *
     * @param text  The text to append.
     * @param color The text color.
     */
    public void append(String text, Color color)
    {
        scrollToBottom();

        Document doc = mLogTextPane.getDocument();

        SimpleAttributeSet style = new SimpleAttributeSet(mBaseAttrs);
        StyleConstants.setForeground(style, color);
        
        try
        {
            doc.insertString(doc.getLength(), text, style);
        }
        catch (BadLocationException ex)
        {
        }
    }

    /**
     * Scroll to the bottom of the text pane, unless the user is dragging the
     * scroll thumb.
     *
     * This doesn't actually perform the scroll; it sets a flag which will trip
     * at the next scroll bar change event.
     */
    protected void scrollToBottom()
    {
        if (!mLogScroller.getVerticalScrollBar().getValueIsAdjusting())
        {
            mShouldScroll = true;
        }
    }

    /**
     * ChangeListener interface method implementation.
     *
     * @param e  The ChangeEvent that was received.
     */
    public void stateChanged(ChangeEvent e)
    {
        // Test for flag. Otherwise, if we scroll unconditionally, the scroll bar will be
        // stuck at the bottom even when the user tries to drag it. So we only scroll
        // when we know we've added text.
        if (mShouldScroll)
        {
            JScrollBar vertBar = mLogScroller.getVerticalScrollBar();
            vertBar.setValue(vertBar.getMaximum());
            mShouldScroll = false;
        }
    }

    /**
     * Populates the pane with UI controls.
     */
    protected void buildUI()
    {
        setLayout(new BorderLayout());

        mLogTextPane = new JTextPane();
        mLogTextPane.setBorder(BorderFactory.createEmptyBorder(1, 4, 1, 4));
        mLogTextPane.setEditable(false);

        mLogScroller = new JScrollPane(mLogTextPane);
        mLogScroller.getVerticalScrollBar().getModel().addChangeListener(this);
        add(mLogScroller, BorderLayout.CENTER);
    }
}
