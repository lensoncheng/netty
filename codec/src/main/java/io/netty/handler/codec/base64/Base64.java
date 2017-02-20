/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
/*
 * Written by Robert Harder and released to the public domain, as explained at
 * http://creativecommons.org/licenses/publicdomain
 */
package io.netty.handler.codec.base64;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import io.netty.util.ByteProcessor;
import io.netty.util.internal.PlatformDependent;

import java.nio.ByteOrder;

/**
 * Utility class for {@link ByteBuf} that encodes and decodes to and from
 * <a href="http://en.wikipedia.org/wiki/Base64">Base64</a> notation.
 * <p>
 * The encoding and decoding algorithm in this class has been derived from
 * <a href="http://iharder.sourceforge.net/current/java/base64/">Robert Harder's Public Domain
 * Base64 Encoder/Decoder</a>.
 */
public final class Base64 {

    /** Maximum line length (76) of Base64 output. */
    private static final int MAX_LINE_LENGTH = 76;

    /** The equals sign (=) as a byte. */
    private static final byte EQUALS_SIGN = (byte) '=';

    /** The new line character (\n) as a byte. */
    private static final byte NEW_LINE = (byte) '\n';

    private static final byte WHITE_SPACE_ENC = -5; // Indicates white space in encoding

    private static final byte EQUALS_SIGN_ENC = -1; // Indicates equals sign in encoding

    private static byte[] alphabet(Base64Dialect dialect) {
        if (dialect == null) {
            throw new NullPointerException("dialect");
        }
        return dialect.alphabet;
    }

    private static byte[] decodabet(Base64Dialect dialect) {
        if (dialect == null) {
            throw new NullPointerException("dialect");
        }
        return dialect.decodabet;
    }

    private static boolean breakLines(Base64Dialect dialect) {
        if (dialect == null) {
            throw new NullPointerException("dialect");
        }
        return dialect.breakLinesByDefault;
    }

    public static ByteBuf encode(ByteBuf src) {
        return encode(src, Base64Dialect.STANDARD);
    }

    public static ByteBuf encode(ByteBuf src, Base64Dialect dialect) {
        return encode(src, breakLines(dialect), dialect);
    }

    public static ByteBuf encode(ByteBuf src, boolean breakLines) {
        return encode(src, breakLines, Base64Dialect.STANDARD);
    }

    public static ByteBuf encode(ByteBuf src, boolean breakLines, Base64Dialect dialect) {

        if (src == null) {
            throw new NullPointerException("src");
        }

        ByteBuf dest = encode(src, src.readerIndex(), src.readableBytes(), breakLines, dialect);
        src.readerIndex(src.writerIndex());
        return dest;
    }

    public static ByteBuf encode(ByteBuf src, int off, int len) {
        return encode(src, off, len, Base64Dialect.STANDARD);
    }

    public static ByteBuf encode(ByteBuf src, int off, int len, Base64Dialect dialect) {
        return encode(src, off, len, breakLines(dialect), dialect);
    }

    public static ByteBuf encode(
            ByteBuf src, int off, int len, boolean breakLines) {
        return encode(src, off, len, breakLines, Base64Dialect.STANDARD);
    }

    public static ByteBuf encode(
            ByteBuf src, int off, int len, boolean breakLines, Base64Dialect dialect) {
        return encode(src, off, len, breakLines, dialect, src.alloc());
    }

    public static ByteBuf encode(
            ByteBuf src, int off, int len, boolean breakLines, Base64Dialect dialect, ByteBufAllocator allocator) {

        if (src == null) {
            throw new NullPointerException("src");
        }
        if (dialect == null) {
            throw new NullPointerException("dialect");
        }

        int len43 = len * 4 / 3;
        ByteBuf dest = allocator.buffer(
                len43 +
                        (len % 3 > 0 ? 4 : 0) + // Account for padding
                        (breakLines ? len43 / MAX_LINE_LENGTH : 0)).order(src.order()); // New lines
        byte[] alphabet = alphabet(dialect);
        int d = 0;
        int e = 0;
        int len2 = len - 2;
        int lineLength = 0;
        for (; d < len2; d += 3, e += 4) {
            encode3to4(src, d + off, 3, dest, e, alphabet);

            lineLength += 4;

            if (breakLines && lineLength == MAX_LINE_LENGTH) {
                dest.setByte(e + 4, NEW_LINE);
                e ++;
                lineLength = 0;
            } // end if: end of line
        } // end for: each piece of array

        if (d < len) {
            encode3to4(src, d + off, len - d, dest, e, alphabet);
            e += 4;
        } // end if: some padding needed

        // Remove last byte if it's a newline
        if (e > 1 && dest.getByte(e - 1) == NEW_LINE) {
            e--;
        }

        return dest.slice(0, e);
    }

    private static void encode3to4(
            ByteBuf src, int srcOffset, int numSigBytes,
            ByteBuf dest, int destOffset, byte[] alphabet) {
        final int inBuff = toInt(src, srcOffset, numSigBytes);

        //           1         2         3
        // 01234567890123456789012345678901 Bit position
        // --------000000001111111122222222 Array position from threeBytes
        // --------|    ||    ||    ||    | Six bit groups to index ALPHABET
        //          >>18  >>12  >> 6  >> 0  Right shift necessary
        //                0x3f  0x3f  0x3f  Additional AND

        // Create buffer with zero-padding if there are only one or two
        // significant bytes passed in the array.
        // We have to shift left 24 in order to flush out the 1's that appear
        // when Java treats a value as negative that is cast from a byte to an int.

        // Packing bytes into an int to reduce bound and reference count checking.
        final int value;
        switch (numSigBytes) {
            case 3:
                value = (alphabet[inBuff >>> 18       ] << 24) |
                        (alphabet[inBuff >>> 12 & 0x3f] << 16) |
                        (alphabet[inBuff >>>  6 & 0x3f] << 8)  |
                        alphabet[inBuff        & 0x3f];
                break;
            case 2:
                value = (alphabet[inBuff >>> 18       ] << 24) |
                        (alphabet[inBuff >>> 12 & 0x3f] << 16) |
                        (alphabet[inBuff >>> 6  & 0x3f] << 8)  |
                        EQUALS_SIGN;
                break;
            case 1:
                value = (alphabet[inBuff >>> 18       ] << 24) |
                        (alphabet[inBuff >>> 12 & 0x3f] << 16) |
                        (EQUALS_SIGN << 8)                     |
                        EQUALS_SIGN;
                break;
            default:
                // NOOP
                return;
        }
        if (dest.order() == ByteOrder.BIG_ENDIAN) {
            dest.setInt(destOffset, value);
        } else {
            dest.setIntLE(destOffset, value);
        }
    }

    private static int toInt(ByteBuf src, int srcOffset, int numSigBytes) {
        final int rvalue;

        switch (numSigBytes) {
            case 1:
                rvalue =  src.getByte(srcOffset) << 24 >>> 8;
                break;
            case 2:
                short value = src.getShort(srcOffset);
                rvalue = (value & 0xff00) << 8 | (value & 0xff) << 8;
                break;
            default:
                if (numSigBytes <= 0) {
                    return 0;
                }
                int v = src.getMedium(srcOffset);
                rvalue = (v & 0xff0000) | (v & 0xff00) | (v & 0xff);
                break;
        }
        return src.order() == ByteOrder.BIG_ENDIAN ? rvalue : ByteBufUtil.swapInt(rvalue);
    }

    public static ByteBuf decode(ByteBuf src) {
        return decode(src, Base64Dialect.STANDARD);
    }

    public static ByteBuf decode(ByteBuf src, Base64Dialect dialect) {

        if (src == null) {
            throw new NullPointerException("src");
        }

        ByteBuf dest = decode(src, src.readerIndex(), src.readableBytes(), dialect);
        src.readerIndex(src.writerIndex());
        return dest;
    }

    public static ByteBuf decode(
            ByteBuf src, int off, int len) {
        return decode(src, off, len, Base64Dialect.STANDARD);
    }

    public static ByteBuf decode(
            ByteBuf src, int off, int len, Base64Dialect dialect) {
        return decode(src, off, len, dialect, src.alloc());
    }

    public static ByteBuf decode(
            ByteBuf src, int off, int len, Base64Dialect dialect, ByteBufAllocator allocator) {

        if (src == null) {
            throw new NullPointerException("src");
        }
        if (dialect == null) {
            throw new NullPointerException("dialect");
        }

        // Using a ByteProcessor to reduce bound and reference count checking.
        return new Decoder().decode(src, off, len, allocator, dialect);
    }

    private static final class Decoder implements ByteProcessor {

        private final byte[] b4 = new byte[4];
        private int b4Posn;
        private byte sbiCrop;
        private byte sbiDecode;
        private byte[] decodabet;
        private int outBuffPosn;
        private ByteBuf dest;

        ByteBuf decode(ByteBuf src, int off, int len, ByteBufAllocator allocator, Base64Dialect dialect) {
            int len34 = (len * 3) >>> 2;
            dest = allocator.buffer(len34).order(src.order()); // Upper limit on size of output

            decodabet = decodabet(dialect);
            try {
                src.forEachByte(off, len, this);
                ByteBuf slice = dest.slice(0, outBuffPosn);
                return slice;
            } catch (Throwable cause) {
                dest.release();
                PlatformDependent.throwException(cause);
                return null;
            }
        }

        @Override
        public boolean process(byte value) throws Exception {
            sbiCrop = (byte) (value & 0x7f); // Only the low seven bits
            sbiDecode = decodabet[sbiCrop];

            if (sbiDecode >= WHITE_SPACE_ENC) { // White space, Equals sign or better
                if (sbiDecode >= EQUALS_SIGN_ENC) { // Equals sign or better
                    b4[b4Posn ++] = sbiCrop;
                    if (b4Posn > 3) { // Quartet built
                        outBuffPosn += decode4to3(b4, 0, dest, outBuffPosn, decodabet);
                        b4Posn = 0;

                        // If that was the equals sign, break out of 'for' loop
                        if (sbiCrop == EQUALS_SIGN) {
                            return false;
                        }
                    }
                }
                return true;
            }
            throw new IllegalArgumentException(
                    "invalid bad Base64 input character: " + (short) (value & 0xFF) + " (decimal)");
        }

        private static int decode4to3(
                byte[] src, int srcOffset,
                ByteBuf dest, int destOffset, byte[] decodabet) {

            if (src[srcOffset + 2] == EQUALS_SIGN) {
                // Example: Dk==
                int outBuff =
                        (decodabet[src[srcOffset    ]] & 0xFF) << 18 |
                        (decodabet[src[srcOffset + 1]] & 0xFF) << 12;

                dest.setByte(destOffset, (byte) (outBuff >>> 16));
                return 1;
            } else if (src[srcOffset + 3] == EQUALS_SIGN) {
                // Example: DkL=
                int outBuff =
                        (decodabet[src[srcOffset    ]] & 0xFF) << 18 |
                        (decodabet[src[srcOffset + 1]] & 0xFF) << 12 |
                        (decodabet[src[srcOffset + 2]] & 0xFF) <<  6;

                // Packing bytes into a short to reduce bound and reference count checking.
                short value = (short) (((byte) (outBuff >> 16)) << 8 |
                        ((byte) (outBuff >>  8)) & 0xFF);
                if (dest.order() == ByteOrder.BIG_ENDIAN) {
                    dest.setShort(destOffset, value);
                } else {
                    dest.setShortLE(destOffset, value);
                }
                return 2;
            } else {
                // Example: DkLE
                final int outBuff;
                try {
                    outBuff =
                            (decodabet[src[srcOffset    ]] & 0xFF) << 18 |
                            (decodabet[src[srcOffset + 1]] & 0xFF) << 12 |
                            (decodabet[src[srcOffset + 2]] & 0xFF) <<  6 |
                             decodabet[src[srcOffset + 3]] & 0xFF;
                } catch (IndexOutOfBoundsException ignored) {
                    throw new IllegalArgumentException("not encoded in Base64");
                }
                // Just directly set it as medium
                if (dest.order() == ByteOrder.BIG_ENDIAN) {
                    dest.setMedium(destOffset, outBuff);
                } else {
                    dest.setMediumLE(destOffset, outBuff);
                }
                return 3;
            }
        }
    }

    private Base64() {
        // Unused
    }
}
