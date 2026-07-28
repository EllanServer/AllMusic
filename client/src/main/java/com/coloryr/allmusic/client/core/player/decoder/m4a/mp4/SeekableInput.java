package com.coloryr.allmusic.client.core.player.decoder.m4a.mp4;

import java.io.IOException;

/**
 * Input whose absolute byte position can be changed without downloading a
 * complete MP4 file first.
 */
public interface SeekableInput {

    void seek(long position) throws IOException;

    long length() throws IOException;
}
