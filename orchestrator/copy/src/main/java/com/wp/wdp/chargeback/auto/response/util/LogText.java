package com.wp.wdp.chargeback.auto.response.util;

/** An exception that gives a logged trace its own clean text in place of its message. */
public interface LogText {

    /** The text a logged trace prints for this exception: fixed text, statuses, codes and counts only. */
    String logText();
}
