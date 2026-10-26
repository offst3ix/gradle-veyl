package net.st3ix.veyl;

import java.io.IOException;

/** Auth / login failure with a user-facing message. */
public final class VeylAuthException extends IOException {
  public enum Kind {
    WRONG_CREDENTIALS,
    ACCOUNT_DISABLED,
    VALIDATION,
    SESSION,
    NOT_SIGNED_IN,
    NETWORK,
    OTHER
  }

  public final Kind kind;
  public final int httpStatus;

  public VeylAuthException(Kind kind, String message) {
    this(kind, message, -1);
  }

  public VeylAuthException(Kind kind, String message, int httpStatus) {
    super(message);
    this.kind = kind;
    this.httpStatus = httpStatus;
  }
}
