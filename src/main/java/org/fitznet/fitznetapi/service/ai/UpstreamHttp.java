package org.fitznet.fitznetapi.service.ai;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;

/**
 * One HTTP/1.1 POST to a node's Ollama over a plain socket, built so that {@link #close()} works
 * from <em>any</em> thread at <em>any</em> moment (connecting, waiting for headers, blocked reading
 * a silent body) and makes the blocked call fail at once.
 *
 * <p>{@code HttpURLConnection} cannot do that: once the response headers are in, its
 * {@code disconnect()} closes the input stream, which waits for the lock held by a thread blocked in
 * {@code read()}, so a cancelled turn would only be noticed when the read timeout fires. Dropping
 * the socket is what makes Ollama stop generating on the GPU, so the turn engine uses this class
 * instead. It speaks only what Ollama needs: {@code Connection: close}, a JSON request body, and a
 * response body that is chunked, length-delimited or read until close.
 */
final class UpstreamHttp implements AutoCloseable {

  private final Socket socket = new Socket();
  private volatile boolean closed;
  private volatile boolean connected;
  private int status;
  private InputStream body;

  /**
   * Connects, sends the request and reads the status line and headers. Blocks for at most the
   * connect timeout plus the read timeout (per read) unless {@link #close()} is called.
   */
  void post(
      String address, String path, byte[] requestBody, Duration connectTimeout, Duration readTimeout)
      throws IOException {
    int colon = address.lastIndexOf(':');
    String host = colon < 0 ? address : address.substring(0, colon);
    if (host.startsWith("[") && host.endsWith("]")) {
      host = host.substring(1, host.length() - 1);
    }
    int port = colon < 0 ? 80 : Integer.parseInt(address.substring(colon + 1));

    socket.connect(new InetSocketAddress(host, port), (int) connectTimeout.toMillis());
    connected = true;
    socket.setSoTimeout((int) readTimeout.toMillis());
    socket.setTcpNoDelay(true);

    OutputStream out = socket.getOutputStream();
    out.write(
        ("POST "
                + path
                + " HTTP/1.1\r\nHost: "
                + address
                + "\r\nContent-Type: application/json\r\nAccept: application/x-ndjson\r\n"
                + "Connection: close\r\nContent-Length: "
                + requestBody.length
                + "\r\n\r\n")
            .getBytes(StandardCharsets.US_ASCII));
    out.write(requestBody);
    out.flush();

    InputStream in = new BufferedInputStream(socket.getInputStream(), 8192);
    String statusLine = readLine(in);
    String[] parts = statusLine.split(" ", 3);
    if (parts.length < 2 || !parts[0].startsWith("HTTP/")) {
      throw new IOException("Node sent a malformed HTTP response");
    }
    try {
      status = Integer.parseInt(parts[1].trim());
    } catch (NumberFormatException ex) {
      throw new IOException("Node sent a malformed HTTP status", ex);
    }
    boolean chunked = false;
    long contentLength = -1;
    String line;
    while (!(line = readLine(in)).isEmpty()) {
      int split = line.indexOf(':');
      if (split <= 0) {
        continue;
      }
      String name = line.substring(0, split).trim().toLowerCase(Locale.ROOT);
      String value = line.substring(split + 1).trim();
      if (name.equals("transfer-encoding") && value.toLowerCase(Locale.ROOT).contains("chunked")) {
        chunked = true;
      } else if (name.equals("content-length")) {
        try {
          contentLength = Long.parseLong(value);
        } catch (NumberFormatException ex) {
          throw new IOException("Node sent a malformed Content-Length", ex);
        }
      }
    }
    if (chunked) {
      body = new ChunkedInputStream(in);
    } else if (contentLength >= 0) {
      body = new LengthInputStream(in, contentLength);
    } else {
      body = in;
    }
  }

  int status() {
    return status;
  }

  /** The decoded response body; reads fail with an {@link IOException} once closed. */
  InputStream body() {
    return body;
  }

  /** The TCP connection was established (later timeouts are read timeouts, not connect ones). */
  boolean connected() {
    return connected;
  }

  boolean isClosed() {
    return closed;
  }

  /** Drops the connection. Idempotent, never blocks, safe from any thread. */
  @Override
  public void close() {
    closed = true;
    try {
      socket.close();
    } catch (IOException ignored) {
      // already gone
    }
  }

  /** A CRLF-terminated ASCII line without its terminator. */
  private static String readLine(InputStream in) throws IOException {
    ByteArrayOutputStream line = new ByteArrayOutputStream();
    int b;
    while ((b = in.read()) != -1) {
      if (b == '\n') {
        byte[] bytes = line.toByteArray();
        int length = bytes.length > 0 && bytes[bytes.length - 1] == '\r' ? bytes.length - 1 : bytes.length;
        return new String(bytes, 0, length, StandardCharsets.ISO_8859_1);
      }
      line.write(b);
      if (line.size() > 16 * 1024) {
        throw new IOException("Node sent an oversized HTTP header line");
      }
    }
    throw new EOFException("Node closed the connection before answering");
  }

  /** Decodes {@code Transfer-Encoding: chunked}; the final zero-size chunk is end of body. */
  private static final class ChunkedInputStream extends InputStream {
    private final InputStream in;
    private long remainingInChunk;
    private boolean finished;

    ChunkedInputStream(InputStream in) {
      this.in = in;
    }

    @Override
    public int read() throws IOException {
      byte[] one = new byte[1];
      int n = read(one, 0, 1);
      return n == -1 ? -1 : one[0] & 0xff;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      if (length == 0) {
        return 0;
      }
      if (finished) {
        return -1;
      }
      if (remainingInChunk == 0) {
        String sizeLine = readLine(in);
        int extension = sizeLine.indexOf(';');
        String hex = (extension >= 0 ? sizeLine.substring(0, extension) : sizeLine).trim();
        try {
          remainingInChunk = Long.parseLong(hex, 16);
        } catch (NumberFormatException ex) {
          throw new IOException("Node sent a malformed chunk size", ex);
        }
        if (remainingInChunk == 0) {
          finished = true;
          return -1;
        }
      }
      int n = in.read(buffer, offset, (int) Math.min(length, remainingInChunk));
      if (n == -1) {
        throw new EOFException("Node closed the connection mid-chunk");
      }
      remainingInChunk -= n;
      if (remainingInChunk == 0) {
        readLine(in); // the CRLF that ends the chunk
      }
      return n;
    }
  }

  /** A body of known length. */
  private static final class LengthInputStream extends InputStream {
    private final InputStream in;
    private long remaining;

    LengthInputStream(InputStream in, long length) {
      this.in = in;
      this.remaining = length;
    }

    @Override
    public int read() throws IOException {
      byte[] one = new byte[1];
      int n = read(one, 0, 1);
      return n == -1 ? -1 : one[0] & 0xff;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      if (length == 0) {
        return 0;
      }
      if (remaining <= 0) {
        return -1;
      }
      int n = in.read(buffer, offset, (int) Math.min(length, remaining));
      if (n == -1) {
        throw new EOFException("Node closed the connection before the end of the body");
      }
      remaining -= n;
      return n;
    }
  }
}
