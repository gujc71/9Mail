package com.ninemail.imap;

/**
 * IMAP session state machine (4-state model)
 */
public enum ImapState {
    /** Immediately after TCP connect - before authentication */
    NOT_AUTHENTICATED,
    /** Authenticated - before mailbox selection */
    AUTHENTICATED,
    /** A mailbox is selected - message operations allowed */
    SELECTED,
    /** Session end */
    LOGOUT
}
