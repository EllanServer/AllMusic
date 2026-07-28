package com.coloryr.allmusic.client.core.player;

final class AudioFormatDetector {
    enum Format {
        MP3,
        M4A,
        OGG,
        FLAC,
        UNKNOWN
    }

    private AudioFormatDetector() {
    }

    static Format detect(byte[] head, int length) {
        int available = Math.min(length, head.length);
        if (available >= 4 && matches(head, 0, 'f', 'L', 'a', 'C')) {
            return Format.FLAC;
        }
        if (available >= 4 && matches(head, 0, 'O', 'g', 'g', 'S')) {
            return Format.OGG;
        }
        if (available >= 3 && matches(head, 0, 'I', 'D', '3')) {
            return Format.MP3;
        }
        if (available >= 2 && isMpegAudioFrameHeader(head[0], head[1])) {
            return Format.MP3;
        }
        if (available >= 8 && matches(head, 4, 'f', 't', 'y', 'p')) {
            return Format.M4A;
        }
        return Format.UNKNOWN;
    }

    static String hexPrefix(byte[] head, int length) {
        int available = Math.min(Math.min(length, head.length), 12);
        StringBuilder result = new StringBuilder(available * 3);
        for (int index = 0; index < available; index++) {
            if (index > 0) {
                result.append(' ');
            }
            int value = head[index] & 0xFF;
            if (value < 0x10) {
                result.append('0');
            }
            result.append(Integer.toHexString(value).toUpperCase());
        }
        return result.toString();
    }

    private static boolean isMpegAudioFrameHeader(byte first, byte second) {
        int byte1 = first & 0xFF;
        int byte2 = second & 0xFF;
        if (byte1 != 0xFF || (byte2 & 0xE0) != 0xE0) {
            return false;
        }

        // MPEG version 01 and layer 00 are reserved. Excluding them avoids
        // treating arbitrary binary data beginning with FF as an MP3 stream.
        return (byte2 & 0x18) != 0x08 && (byte2 & 0x06) != 0;
    }

    private static boolean matches(byte[] data, int offset, char... expected) {
        if (offset + expected.length > data.length) {
            return false;
        }
        for (int index = 0; index < expected.length; index++) {
            if ((data[offset + index] & 0xFF) != expected[index]) {
                return false;
            }
        }
        return true;
    }
}
