package io.stackvibed.viewer.registry

/** No workspace in the list has this name; the API answers 404. */
class UnknownWorkspaceException(name: String) : NoSuchElementException("No workspace named '$name'")
