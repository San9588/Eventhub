package com.eventsh.app.engine;

/**
 * Runs inside the Shizuku server process (shell / root uid) so the app can
 * execute privileged shell commands without holding root itself.
 */
interface ICommandService {
    String execute(String command);
}
