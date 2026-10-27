package org.springframework.core.io;

/** Test stand-in for Spring's type (the extension matches it by name and never links against Spring). */
public interface InputStreamSource {
    java.io.InputStream getInputStream() throws java.io.IOException;
}
