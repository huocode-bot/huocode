package io.huocode.api.exception;

import lombok.Getter;

@Getter
public class GitCommandFailedException extends RuntimeException {

  private final String command;
  private final String output;

  public GitCommandFailedException(String command, String output) {
    super("git command failed: " + command + System.lineSeparator() + output);
    this.command = command;
    this.output = output;
  }
}
