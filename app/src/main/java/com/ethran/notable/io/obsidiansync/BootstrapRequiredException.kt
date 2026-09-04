package com.ethran.notable.io.obsidiansync

/** Thrown when push is attempted before the vault has been bootstrapped via pull. */
class BootstrapRequiredException(message: String) : IllegalStateException(message)
