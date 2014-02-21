/**
 * Copyright 2014 JogAmp Community. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 *
 *    1. Redistributions of source code must retain the above copyright notice, this list of
 *       conditions and the following disclaimer.
 *
 *    2. Redistributions in binary form must reproduce the above copyright notice, this list
 *       of conditions and the following disclaimer in the documentation and/or other materials
 *       provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY JogAmp Community ``AS IS'' AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL JogAmp Community OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 * The views and conclusions contained in the software and documentation are those of the
 * authors and should not be interpreted as representing official policies, either expressed
 * or implied, of JogAmp Community.
 */
package com.jogamp.common.util;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

import jogamp.common.Debug;

/**
 * Versatile Bitstream implementation supporting:
 * <ul>
 *   <li>Utilize I/O operations on I/O streams, buffers and arrays</li>
 *   <li>Consider MSBfirst / LSBfirst mode</li>
 *   <li>Linear bit R/W operations</li>
 *   <li>Bulk R/W operations w/ endian related type conversion</li>
 *   <li>Allow mark/reset and switching streams and input/output mode</li>
 *   <li>Optimized operations</li>
 * </ul>
 */
public class Bitstream<T> {
    private static final boolean DEBUG = Debug.debug("Bitstream");

    /** End of stream marker, {@value} or 0xFFFFFFFF */
    public static final int EOS = -1;

    /**
     * General byte stream.
     */
    public static interface ByteStream<T> {
        /** Sets the underlying stream, without {@link #close()}ing the previous one. */
        void setStream(final T stream);

        /** Returns the underlying stream */
        T getStream();

        /**
         * Closing the underlying stream, implies {@link #flush()}.
         * <p>
         * Implementation will <code>null</code> the stream references,
         * hence {@link #setStream(Object)} must be called before re-using instance.
         * </p>
         * @throws IOException
         */
        void close() throws IOException;

        /**
         * Synchronizes all underlying {@link #canOutput() output stream} operations, or do nothing.
         * @throws IOException
         */
        void flush() throws IOException;

        /** Return true if stream can handle input, i.e. {@link #read()}. */
        boolean canInput();

        /** Return true if stream can handle output, i.e. {@link #write(byte)} */
        boolean canOutput();

        /**
         * Returns the byte position in the stream.
         */
        long position();

        /**
         * It is implementation dependent, whether backward skip giving a negative number is supported or not.
         * @param n number of bytes to skip
         * @return actual skipped bytes
         * @throws IOException
         */
        long skip(final long n) throws IOException;

        /**
         * Set <i>markpos</i> to current position, allowing the stream to be {@link #reset()}.
         * @param readLimit
         * @throws UnsupportedOperationException is not supported, i.e. if stream is not an {@link #canInput() input stream}.
         */
        void mark(final int readLimit) throws UnsupportedOperationException;

        /**
         * Reset stream position to <i>markpos</i> as set via {@link #mark(int)}.
         * <p>
         * <i>markpos</i> is kept, hence {@link #reset()} can be called multiple times.
         * </p>
         * @throws UnsupportedOperationException is not supported, i.e. if stream is not an {@link #canInput() input stream}.
         * @throws IllegalStateException if <i>markpos</i> has not been set via {@link #mark(int)} or reset operation failed.
         * @throws IOException if reset operation failed.
         */
        void reset() throws UnsupportedOperationException, IllegalStateException, IOException;

        /**
         * Reads one byte from the stream.
         * <p>
         * Returns {@link Bitstream#EOS} is end-of-stream is reached,
         * otherwise the resulting value.
         * </p>
         * @throws IOException
         * @throws UnsupportedOperationException is not supported, i.e. if stream is not an {@link #canInput() input stream}.
         */
        int read() throws UnsupportedOperationException, IOException;

        /**
         * Writes one byte, to the stream.
         * <p>
         * Returns {@link Bitstream#EOS} is end-of-stream is reached,
         * otherwise the written value.
         * </p>
         * @throws IOException
         * @throws UnsupportedOperationException is not supported, i.e. if stream is not an {@link #canOutput() output stream}.
         */
        int write(final byte val) throws UnsupportedOperationException, IOException;
    }

    /**
     * Specific {@link ByteStream byte stream}.
     * <p>
     * Can handle {@link #canInput() input} and {@link #canOutput() output} operations.
     * </p>
     */
    public static class ByteArrayStream implements ByteStream<byte[]> {
        private byte[] media;
        private int pos;
        private int posMark;

        public ByteArrayStream(final byte[] stream) {
            setStream(stream);
        }

        @Override
        public void setStream(final byte[] stream) {
            media = stream;
            pos = 0;
            posMark = -1;
        }

        @Override
        public byte[] getStream() { return media; }

        @Override
        public void close() {
            media = null;
        }
        @Override
        public void flush() {
            // NOP
        }

        @Override
        public boolean canInput() { return true; }

        @Override
        public boolean canOutput() { return true; }

        @Override
        public long position() { return pos; }

        @Override
        public long skip(final long n) {
            final long skip;
            if( n >= 0 ) {
                final int remaining = media.length - pos;
                skip = Math.min(remaining, (int)n);
            } else {
                final int n2 = (int)n * -1;
                skip = -1 * Math.min(pos, n2);
            }
            pos += skip;
            return skip;
        }

        @Override
        public void mark(final int readLimit) {
            posMark = pos;
        }

        @Override
        public void reset() throws IllegalStateException {
            if( 0 > posMark ) {
                throw new IllegalStateException("markpos not set");
            }
            if(DEBUG) { System.err.println("rewind: "+pos+" -> "+posMark); }
            pos = posMark;
        }

        @Override
        public int read() {
            final int r;
            if( media.length > pos ) {
                r = 0xff & media[pos++];
            } else {
                r = -1; // EOS
            }
            if( DEBUG ) {
                if( EOS != r ) {
                    System.err.println("u8["+(pos-1)+"] -> "+toHexBinString(r, 8));
                } else {
                    System.err.println("u8["+(pos-0)+"] -> EOS");
                }
            }
            return r;
        }

        @Override
        public int write(final byte val) {
            final int r;
            if( media.length > pos ) {
                media[pos++] = val;
                r = 0xff & val;
            } else {
                r = -1; // EOS
            }
            if( DEBUG ) {
                if( EOS != r ) {
                    System.err.println("u8["+(pos-1)+"] <- "+toHexBinString(r, 8));
                } else {
                    System.err.println("u8["+(pos-0)+"] <- EOS");
                }
            }
            return r;
        }
    }

    /**
     * Specific {@link ByteStream byte stream}.
     * <p>
     * Can handle {@link #canInput() input} and {@link #canOutput() output} operations.
     * </p>
     */
    public static class ByteBufferStream implements ByteStream<ByteBuffer> {
        private ByteBuffer media;
        private int pos;
        private int posMark;

        public ByteBufferStream(final ByteBuffer stream) {
            setStream(stream);
        }

        @Override
        public void setStream(final ByteBuffer stream) {
            media = stream;
            pos = 0;
            posMark = -1;
        }

        @Override
        public ByteBuffer getStream() { return media; }

        @Override
        public void close() {
            media = null;
        }
        @Override
        public void flush() {
            // NOP
        }

        @Override
        public boolean canInput() { return true; }

        @Override
        public boolean canOutput() { return true; }

        @Override
        public long position() { return pos; }

        @Override
        public long skip(final long n) {
            final long skip;
            if( n >= 0 ) {
                final int remaining = media.limit() - pos;
                skip = Math.min(remaining, (int)n);
            } else {
                final int n2 = (int)n * -1;
                skip = -1 * Math.min(pos, n2);
            }
            pos += skip;
            return skip;
        }

        @Override
        public void mark(final int readLimit) {
            posMark = pos;
        }

        @Override
        public void reset() throws IllegalStateException {
            if( 0 > posMark ) {
                throw new IllegalStateException("markpos not set");
            }
            if(DEBUG) { System.err.println("rewind: "+pos+" -> "+posMark); }
            media.position(posMark);
            pos = posMark;
        }

        @Override
        public int read() {
            final int r;
            if( media.limit() > pos ) {
                r = 0xff & media.get(pos++);
            } else {
                r = -1; // EOS
            }
            if( DEBUG ) {
                if( EOS != r ) {
                    System.err.println("u8["+(pos-1)+"] -> "+toHexBinString(r, 8));
                } else {
                    System.err.println("u8["+(pos-0)+"] -> EOS");
                }
            }
            return r;
        }

        @Override
        public int write(final byte val) {
            final int r;
            if( media.limit() > pos ) {
                media.put(pos++, val);
                r = 0xff & val;
            } else {
                r = -1; // EOS
            }
            if( DEBUG ) {
                if( EOS != r ) {
                    System.err.println("u8["+(pos-1)+"] <- "+toHexBinString(r, 8));
                } else {
                    System.err.println("u8["+(pos-0)+"] <- EOS");
                }
            }
            return r;
        }
    }

    /**
     * Specific {@link ByteStream byte stream}.
     * <p>
     * Can handle {@link #canInput() input} operations only.
     * </p>
     */
    public static class ByteInputStream implements ByteStream<InputStream> {
        private BufferedInputStream media;
        private long pos;
        private long posMark;

        public ByteInputStream(final InputStream stream) {
            setStream(stream);
        }

        @Override
        public void setStream(final InputStream stream) {
            if( stream instanceof BufferedInputStream ) {
                media = (BufferedInputStream) stream;
            } else if( null != stream ) {
                media = new BufferedInputStream(stream);
            } else {
                media = null;
            }
            pos = 0;
            posMark = -1;
        }

        @Override
        public InputStream getStream() { return media; }

        @Override
        public void close() throws IOException {
            if( null != media ) {
                media.close();
                media = null;
            }
        }
        @Override
        public void flush() {
            // NOP
        }

        @Override
        public boolean canInput() { return true; }

        @Override
        public boolean canOutput() { return false; }

        @Override
        public long position() { return pos; }

        @Override
        public long skip(final long n) throws IOException {
            final long skip = media.skip(n);
            pos += skip;
            return skip;
        }

        @Override
        public void mark(final int readLimit) {
            media.mark(readLimit);
            posMark = pos;
        }

        @Override
        public void reset() throws IllegalStateException, IOException {
            if( 0 > posMark ) {
                throw new IllegalStateException("markpos not set");
            }
            if(DEBUG) { System.err.println("rewind: "+pos+" -> "+posMark); }
            media.reset();
            pos = posMark;
        }

        @Override
        public int read() throws IOException {
            final int r = media.read();
            if(DEBUG) {
                if( EOS != r ) {
                    System.err.println("u8["+pos+"] -> "+toHexBinString(r, 8));
                } else {
                    System.err.println("u8["+pos+"] -> EOS");
                }
            }
            if( EOS != r ) {
                pos++;
            }
            return r;
        }

        @Override
        public int write(final byte val) throws UnsupportedOperationException {
            throw new UnsupportedOperationException("not allowed with input stream");
        }
    }

    /**
     * Specific {@link ByteStream byte stream}.
     * <p>
     * Can handle {@link #canOutput() output} operations only.
     * </p>
     */
    public static class ByteOutputStream implements ByteStream<OutputStream> {
        private BufferedOutputStream media;
        private long pos = 0;

        public ByteOutputStream(final OutputStream stream) {
            setStream(stream);
        }

        @Override
        public void setStream(final OutputStream stream) {
            if( stream instanceof BufferedOutputStream ) {
                media = (BufferedOutputStream) stream;
            } else if( null != stream ) {
                media = new BufferedOutputStream(stream);
            } else {
                media = null;
            }
            pos = 0;
        }

        @Override
        public void close() throws IOException {
            if( null != media ) {
                media.close();
                media = null;
            }
        }
        @Override
        public void flush() throws IOException {
            if( null != media ) {
                media.flush();
            }
        }

        @Override
        public boolean canInput() { return false; }

        @Override
        public boolean canOutput() { return true; }

        @Override
        public long position() { return pos; }

        @Override
        public long skip(final long n) throws IOException {
            long i = n;
            while(i > 0) {
                media.write(0);
                i--;
            }
            final long skip = n-i; // should be n
            pos += skip;
            return skip;
        }

        @Override
        public OutputStream getStream() { return media; }

        @Override
        public void mark(final int readLimit) throws UnsupportedOperationException {
            throw new UnsupportedOperationException("not allowed with output stream");
        }

        @Override
        public void reset() throws UnsupportedOperationException {
            throw new UnsupportedOperationException("not allowed with output stream");
        }

        @Override
        public int read() throws UnsupportedOperationException {
            throw new UnsupportedOperationException("not allowed with output stream");
        }

        @Override
        public int write(final byte val) throws IOException {
            final int r = 0xff & val;
            media.write(r);
            if(DEBUG) {
                System.err.println("u8["+pos+"] <- "+toHexBinString(r, 8));
            }
            pos++;
            return r;
        }
    }

    private ByteStream<T> bytes;
    /** 8-bit cache of byte stream */
    private int bitBuffer;
    private int bitsDataMark;

    /** See {@link #getBitCount()}. */
    private int bitCount;
    private int bitsCountMark;

    private boolean outputMode;

    /**
     * @param stream
     * @param outputMode
     * @throws IllegalArgumentException if requested <i>outputMode</i> doesn't match stream's {@link #canInput()} and {@link #canOutput()}.
     */
    public Bitstream(final ByteStream<T> stream, final boolean outputMode) throws IllegalArgumentException {
        this.bytes = stream;
        this.outputMode = outputMode;
        resetLocal();
        validateMode();
    }

    private final void resetLocal() {
        bitBuffer = 0;
        bitCount = 0;
        bitsDataMark = 0;
        bitsCountMark = -1;
    }
    private final void validateMode() throws IllegalArgumentException {
        if( !canInput() && !canOutput() ) {
            throw new IllegalArgumentException("stream can neither input nor output: "+this);
        }
        if( outputMode && !canOutput() ) {
            throw new IllegalArgumentException("stream cannot output as requested: "+this);
        }
        if( !outputMode && !canInput() ) {
            throw new IllegalArgumentException("stream cannot input as requested: "+this);
        }
    }

    /**
     * Sets the underlying stream, without {@link #close()}ing the previous one.
     * <p>
     * If the previous stream was in {@link #canOutput() output mode},
     * {@link #flush()} is being called.
     * </p>
     * @throws IllegalArgumentException if requested <i>outputMode</i> doesn't match stream's {@link #canInput()} and {@link #canOutput()}.
     * @throws IOException could be caused by {@link #flush()}.
     */
    public final void setStream(final T stream, final boolean outputMode) throws IllegalArgumentException, IOException {
        if( null != bytes && this.outputMode ) {
            flush();
        }
        this.bytes.setStream(stream);
        this.outputMode = outputMode;
        resetLocal();
        validateMode();
    }

    /** Returns the currently used {@link ByteStream}. */
    public final ByteStream<T> getStream() { return bytes; }

    /** Returns the currently used {@link ByteStream}'s {@link ByteStream#getStream()}. */
    public final T getSubStream() { return bytes.getStream(); }

    /**
     * Closing the underlying stream, implies {@link #flush()}.
     * <p>
     * Implementation will <code>null</code> the stream references,
     * hence {@link #setStream(Object)} must be called before re-using instance.
     * </p>
     * <p>
     * If the closed stream was in {@link #canOutput() output mode},
     * {@link #flush()} is being called.
     * </p>
     *
     * @throws IOException
     */
    public final void close() throws IOException {
        if( null != bytes && this.outputMode ) {
            flush();
        }
        bytes.close();
        bytes = null;
        resetLocal();
    }

    /**
     * Synchronizes all underlying {@link ByteStream#canOutput() output stream} operations, or do nothing.
     * <p>
     * Method also flushes incomplete bytes to the underlying {@link ByteStream}
     * and hence skips to the next byte position.
     * </p>
     * @throws IllegalStateException if not in output mode or stream closed
     * @throws IOException
     */
    public final void flush() throws IllegalStateException, IOException {
        if( !outputMode || null == bytes ) {
            throw new IllegalStateException("not in output-mode: "+this);
        }
        bytes.flush();
        if( 0 != bitCount ) {
            bytes.write((byte)bitBuffer);
            bitBuffer = 0;
            bitCount = 0;
        }
    }

    /** Return true if stream can handle input, i.e. {@link #readBit(boolean)}. */
    public final boolean canInput() { return null != bytes ? bytes.canInput() : false; }

    /** Return true if stream can handle output, i.e. {@link #writeBit(boolean, int)}. */
    public final boolean canOutput() { return null != bytes ? bytes.canOutput() : false; }

    /**
     * Set <i>markpos</i> to current position, allowing the stream to be {@link #reset()}.
     * @param readLimit
     * @throws IllegalStateException if not in input mode or stream closed
     */
    public final void mark(final int readLimit) throws IllegalStateException {
        if( outputMode || null == bytes ) {
            throw new IllegalStateException("not in input-mode: "+this);
        }
        bytes.mark(readLimit);
        bitsDataMark = bitBuffer;
        bitsCountMark = bitCount;
    }

    /**
     * Reset stream position to <i>markpos</i> as set via {@link #mark(int)}.
     * <p>
     * <i>markpos</i> is kept, hence {@link #reset()} can be called multiple times.
     * </p>
     * @throws IllegalStateException if not in input mode or stream closed
     * @throws IllegalStateException if <i>markpos</i> has not been set via {@link #mark(int)} or reset operation failed.
     * @throws IOException if reset operation failed.
     */
    public final void reset() throws IllegalStateException, IOException {
        if( outputMode || null == bytes ) {
            throw new IllegalStateException("not in input-mode: "+this);
        }
        if( 0 > bitsCountMark ) {
            throw new IllegalStateException("markpos not set: "+this);
        }
        bytes.reset();
        bitBuffer = bitsDataMark;
        bitCount = bitsCountMark;
    }

    /**
     * Number of remaining bits in cache to read before next byte-read (input mode)
     * or number of remaining bits to be cached before next byte-write (output mode).
     * <p>
     * Counting down from 7..0 7..0, starting with 0.
     * </p>
     * <p>
     * In input mode, zero indicates reading a new byte and cont. w/ 7.
     * In output mode, the cached byte is written when flipping over to 0.
     * </p>
     */
    public final int getBitCount() { return bitCount; }

    /**
     * Return the last bit number read or written counting from [0..7].
     * If no bit access has been performed, 7 is returned.
     * <p>
     * Returned value is normalized [0..7], i.e. independent from <i>msb</i> or <i>lsb</i> read order.
     * </p>
     */
    public final int getLastBitPos() { return 7 - bitCount; }

    /**
     * Return the next bit number to be read or write counting from [0..7].
     * If no bit access has been performed, 0 is returned.
     * <p>
     * Returned value is normalized [0..7], i.e. independent from <i>msb</i> or <i>lsb</i> read order.
     * </p>
     */
    public final int getBitPosition() {
        if( 0 == bitCount ) {
            return 0;
        } else {
            return 8 - bitCount;
        }
    }

    /**
     * Returns the current bit buffer.
     * @see #getBitCount()
     */
    public final int getBitBuffer() { return bitBuffer; }

    /**
     * Returns the bit position in the stream.
     */
    public final long position() {
        // final long bytePos = bytes.position() - ( !outputMode && 0 != bitCount ? 1 : 0 );
        // return ( bytePos << 3 ) + getBitPosition();
        if( null == bytes ) {
            return EOS;
        } else if( 0 == bitCount ) {
            return bytes.position() << 3;
        } else {
            final long bytePos = bytes.position() - ( outputMode ? 0 : 1 );
            return ( bytePos << 3 ) + 8 - bitCount;
        }
    }

    /**
     * @param msbFirst if true incoming stream bit order is MSB to LSB, otherwise LSB to MSB.
     * @return the read bit or {@link #EOS} if end-of-stream is reached.
     * @throws IOException
     * @throws IllegalStateException if not in input mode or stream closed
     */
    public final int readBit(final boolean msbFirst) throws IllegalStateException, IOException {
        if( outputMode || null == bytes ) {
            throw new IllegalStateException("not in input-mode: "+this);
        }
        if( msbFirst ) {
            // MSB
            if ( 0 < bitCount ) {
                bitCount--;
                return  ( bitBuffer >>> bitCount ) & 0x01;
            } else {
                bitBuffer = bytes.read();
                if( EOS == bitBuffer ) {
                    return EOS;
                } else {
                    bitCount=7;
                    return bitBuffer >>> 7;
                }
            }
        } else {
            // LSB
            if ( 0 < bitCount ) {
                bitCount--;
                return  ( bitBuffer >>> ( 7 - bitCount ) ) & 0x01;
            } else {
                bitBuffer = bytes.read();
                if( EOS == bitBuffer ) {
                    return EOS;
                } else {
                    bitCount=7;
                    return bitBuffer & 0x01;
                }
            }
        }
    }

    /**
     * @param msbFirst if true outgoing stream bit order is MSB to LSB, otherwise LSB to MSB.
     * @param bit
     * @return the currently written byte or {@link #EOS} if end-of-stream is reached.
     * @throws IOException
     * @throws IllegalStateException if not in output mode or stream closed
     */
    public final int writeBit(final boolean msbFirst, final int bit) throws IllegalStateException, IOException {
        if( !outputMode || null == bytes ) {
            throw new IllegalStateException("not in output-mode: "+this);
        }
        if( msbFirst ) {
            // MSB
            if ( 0 < bitCount ) {
                bitCount--;
                bitBuffer |= ( 0x01 & bit ) << bitCount;
                if( 0 == bitCount ) {
                    return bytes.write((byte)bitBuffer);
                }
            } else {
                bitCount = 7;
                bitBuffer = ( 0x01 & bit ) << 7;
            }
        } else {
            // LSB
            if ( 0 < bitCount ) {
                bitCount--;
                bitBuffer |= ( 0x01 & bit ) << ( 7 - bitCount );
                if( 0 == bitCount ) {
                    return bytes.write((byte)bitBuffer);
                }
            } else {
                bitCount = 7;
                bitBuffer = 0x01 & bit;
            }
        }
        return bitBuffer;
    }

    /**
     * It is implementation dependent, whether backward skip giving a negative number is supported or not.
     *
     * @param n number of bits to skip
     * @return actual skipped bits
     * @throws IOException
     * @throws IllegalStateException if closed
     */
    public long skip(final long n) throws IllegalStateException, IOException {
        if( null == bytes ) {
            throw new IllegalStateException("closed: "+this);
        }
        if( DEBUG ) {
            System.err.println("Bitstream.skip.0: "+n+" - "+toStringImpl());
        }
        if( n > 0 ) {
            if( n <= bitCount ) {
                bitCount -= (int)n;
                if( DEBUG ) {
                    System.err.println("Bitstream.skip.F_N1: "+n+" - "+toStringImpl());
                }
                return n;
            } else { // n > bitCount
                if( outputMode ) {
                    if( 0 < bitCount ) {
                        bytes.write((byte)bitBuffer);
                    }
                    bitBuffer = 0;
                }
                final long n2 = n - bitCount;                // subtract cached bits, bitsCount is zero at this point
                final long n3 = n2 >>> 3;                    // bytes to skip
                final long n4 = bytes.skip(n3);              // actual skipped bytes
                final int n5 = (int) ( n2 - ( n3 << 3 ) );   // remaining skip bits == nX % 8
                final long nX = ( n4 << 3 ) + n5 + bitCount; // actual skipped bits
                /**
                if( DEBUG ) {
                    System.err.println("Bitstream.skip.1: n2 "+n2+", n3 "+n3+", n4 "+n4+", n5 "+n5+", nX "+nX+" - "+toStringImpl());
                } */
                if( nX < n ) {
                    // couldn't complete skipping .. EOS .. etc
                    bitCount = 0;
                    bitBuffer = 0;
                    if( DEBUG ) {
                        System.err.println("Bitstream.skip.F_EOS: "+n+" - "+toStringImpl());
                    }
                    return nX;
                }
                bitCount = ( 8 - n5 ) & 7; // % 8
                if( !outputMode && 0 < bitCount ) {
                    bitBuffer = bytes.read();
                }
                if( DEBUG ) {
                    System.err.println("Bitstream.skip.F_N2: "+n+" - "+toStringImpl());
                }
                return nX;
            }
        } else {
            // FIXME: Backward skip
            return 0;
        }
    }

    /**
     * Return incoming bits as read via {@link #readBit(boolean)}.
     * <p>
     * The incoming bits are stored in MSB-first order, i.e. first on highest position and last bit on lowest position.
     * Hence reading w/ <i>lsbFirst</i>, the bit order will be reversed!
     * </p>
     * @param msbFirst if true incoming stream bit order is MSB to LSB, otherwise LSB to MSB.
     * @param n number of bits, maximum 31 bits
     * @return the read bits from 0-n in the given order or {@link #EOS}.
     * @throws IllegalStateException if not in input mode or stream closed
     * @throws IllegalArgumentException if n > 31
     * @throws IOException
     */
    public int readBits31(final boolean msbFirst, final int n) throws IllegalArgumentException, IOException {
        if( 31 < n ) {
            throw new IllegalArgumentException("n > 31: "+n);
        }
        if( outputMode || null == bytes ) {
            throw new IllegalStateException("not in input-mode: "+this);
        }
        if( !msbFirst || 0 == n ) {
            // Slow path
            int r = 0;
            int c = n;
            while(--c >= 0) {
                final int b = readBit(msbFirst);
                if( EOS == b ) {
                    return EOS;
                }
                r |= b << c;
            }
            return r;
        } else {
            // fast path: MSB
            int c = n;
            final int n1 = Math.min(c, bitCount); // remaining portion
            int r;
            if( 0 < n1 ) {
                final int m1 = ( 1 << n1 ) - 1;
                bitCount -= n1;
                c -= n1;
                r = ( m1 & ( bitBuffer >>> bitCount ) ) << c;
                if( 0 == c ) {
                    return r;
                }
            } else {
                r = 0;
            }
            assert( 0 == bitCount );
            do {
                bitBuffer = bytes.read();
                if( EOS == bitBuffer ) {
                    return EOS;
                }
                final int n2 = Math.min(c, 8); // full portion
                final int m2 = ( 1 << n2 ) - 1;
                bitCount = 8 - n2;
                c -= n2;
                r |= ( m2 & ( bitBuffer >>> bitCount ) ) << c;
            } while ( 0 < c );
            return r;
        }
    }

    /**
     * Write the given bits via {@link #writeBit(boolean, int)}.
     * <p>
     * The given bits are scanned from LSB-first order.
     * Hence reading w/ <i>msbFirst</i>, the bit order will be reversed!
     * </p>
     * @param msbFirst if true incoming stream bit order is MSB to LSB, otherwise LSB to MSB.
     * @param n number of bits, maximum 31 bits
     * @param bits the bits to write
     * @return the written bits or {@link #EOS}.
     * @throws IllegalStateException if not in output mode or stream closed
     * @throws IllegalArgumentException if n > 31
     * @throws IOException
     */
    public int writeBits31(final boolean msbFirst, final int n, final int bits) throws IllegalStateException, IllegalArgumentException, IOException {
        if( 31 < n ) {
            throw new IllegalArgumentException("n > 31: "+n);
        }
        if( !outputMode || null == bytes ) {
            throw new IllegalStateException("not in output-mode: "+this);
        }
        if( !msbFirst || 0 == n ) {
            // Slow path
            int c = n;
            while(--c >= 0) {
                final int b = writeBit(msbFirst, ( bits >>> c ) & 0x1);
                if( EOS == b ) {
                    return EOS;
                }
            }
        } else {
            // fast path: MSB
            int c = n;
            final int n1 = Math.min(c, bitCount); // remaining portion
            if( 0 < n1 ) {
                final int m1 = ( 1 << n1 ) - 1;
                bitCount -= n1;
                c -= n1;
                bitBuffer |= ( m1 & ( bits >> c ) ) << bitCount;
                if( 0 == bitCount ) {
                    if( EOS == bytes.write((byte)bitBuffer) ) {
                        return EOS;
                    }
                }
                if( 0 == c ) {
                    return bits;
                }
            }
            assert( 0 == bitCount );
            do {
                final int n2 = Math.min(c, 8); // full portion
                final int m2 = ( 1 << n2 ) - 1;
                bitCount = 8 - n2;
                c -= n2;
                bitBuffer = ( m2 & ( bits >> c ) ) << bitCount;
                if( 0 == bitCount ) {
                    if( EOS == bytes.write((byte)bitBuffer) ) {
                        return EOS;
                    }
                }
            } while ( 0 < c );
        }
        return bits;
    }

    /**
     * Return incoming int8 as read via {@link #readBits31(boolean, int)}.
     * @param msbFirst if true incoming stream bit order is MSB to LSB, otherwise LSB to MSB.
     * @return {@link #EOS} or the 8bit value, which might be unsigned or 2-complement signed value.
     *         In the signed case, user shall cast the result to <code>byte</code>.
     * @throws IllegalStateException if not in input mode or stream closed
     * @throws IOException
     */
    public final int readInt8(final boolean msbFirst) throws IllegalStateException, IOException {
        if( 0 == bitCount && msbFirst ) {
            // fast path
            if( outputMode || null == bytes ) {
                throw new IllegalStateException("not in input-mode: "+this);
            }
            return bytes.read();
        } else {
            return readBits31(msbFirst, 8);
        }
    }

    /**
     * Write the given int8 via {@link #writeBits31(boolean, int, int)}.
     * @param msbFirst if true incoming stream bit order is MSB to LSB, otherwise LSB to MSB.
     * @return {@link #EOS} or the written 8bit value.
     * @throws IllegalStateException if not in output mode or stream closed
     * @throws IOException
     */
    public final int writeInt8(final boolean msbFirst, final byte int8) throws IllegalStateException, IOException {
        if( 0 == bitCount && msbFirst ) {
            // fast path
            if( !outputMode || null == bytes ) {
                throw new IllegalStateException("not in output-mode: "+this);
            }
            return bytes.write(int8);
        } else {
            return this.writeBits31(msbFirst, 8, int8);
        }
    }

    /**
     * Return incoming int16 as read via {@link #readBits31(boolean, int)}
     * and swap bytes if !bigEndian.
     * @param msbFirst if true incoming stream bit order is MSB to LSB, otherwise LSB to MSB.
     * @param bigEndian if false, swap incoming bytes to little-endian, otherwise leave them as little-endian.
     * @return {@link #EOS} or the 16bit value, which might be unsigned or 2-complement signed value.
     *         In the signed case, user shall cast the result to <code>short</code>.
     * @throws IllegalStateException if not in input mode or stream closed
     * @throws IOException
     */
    public final int readInt16(final boolean msbFirst, final boolean bigEndian) throws IllegalStateException, IOException {
        if( 0 == bitCount && msbFirst ) {
            // fast path
            if( outputMode || null == bytes ) {
                throw new IllegalStateException("not in input-mode: "+this);
            }
            final int b1 = bytes.read();
            final int b2 = EOS != b1 ? bytes.read() : EOS;
            if( EOS == b2 ) {
                return EOS;
            } else if( bigEndian ) {
                return b1 << 8 | b2;
            } else {
                return b2 << 8 | b1;
            }
        } else {
            final int i16 = readBits31(msbFirst, 16);
            if( EOS == i16 ) {
                return EOS;
            } else if( bigEndian ) {
                return i16;
            } else {
                final int b1 = 0xff & ( i16 >>> 8 );
                final int b2 = 0xff &   i16;
                return b2 << 8 | b1;
            }
        }
    }

    /**
     * Return incoming int16 value and swap bytes if !bigEndian.
     * @param bigEndian if false, swap incoming bytes to little-endian, otherwise leave them as little-endian.
     * @return the 16bit value, which might be unsigned or 2-complement signed value.
     *         In the signed case, user shall cast the result to <code>short</code>.
     * @throws IndexOutOfBoundsException
     */
    public static final int readInt16(final boolean bigEndian, final byte[] bytes, final int offset) throws IndexOutOfBoundsException {
        checkBounds(bytes, offset, 2);
        final int b1 = bytes[offset];
        final int b2 = bytes[offset+1];
        if( bigEndian ) {
            return b1 << 8 | b2;
        } else {
            return b2 << 8 | b1;
        }
    }

    /**
     * Write the given int16 via {@link #writeBits31(boolean, int, int)},
     * while swapping bytes if !bigEndian beforehand.
     * @param msbFirst if true incoming stream bit order is MSB to LSB, otherwise LSB to MSB.
     * @param bigEndian if false, swap given bytes to little-endian, otherwise leave them as little-endian.
     * @return {@link #EOS} or the written 16bit value.
     * @throws IllegalStateException if not in output mode or stream closed
     * @throws IOException
     */
    public final int writeInt16(final boolean msbFirst, final boolean bigEndian, final short int16) throws IllegalStateException, IOException {
        if( 0 == bitCount && msbFirst ) {
            // fast path
            if( !outputMode || null == bytes ) {
                throw new IllegalStateException("not in output-mode: "+this);
            }
            final byte hi = (byte) ( 0xff & ( int16 >>> 8 ) );
            final byte lo = (byte) ( 0xff &   int16         );
            final byte b1, b2;
            if( bigEndian ) {
                b1 = hi;
                b2 = lo;
            } else {
                b1 = lo;
                b2 = hi;
            }
            if( EOS != bytes.write(b1) ) {
                if( EOS != bytes.write(b2) ) {
                    return int16;
                }
            }
            return EOS;
        } else if( bigEndian ) {
            return writeBits31(msbFirst, 16, int16);
        } else {
            final int b1 = 0xff & ( int16 >>> 8 );
            final int b2 = 0xff &   int16;
            return writeBits31(msbFirst, 16, b2 << 8 | b1);
        }
    }

    /**
     * Return incoming int32 as read via {@link #readBits31(boolean, int)}
     * and swap bytes if !bigEndian.
     * <p>
     * In case the returned value shall be interpreted as <code>uint32_t</code>
     * utilize {@link #toUInt32Long(int)} or {@link #toUInt32Int(int)} for
     * an appropriate conversion.
     * </p>
     * @param msbFirst if true incoming stream bit order is MSB to LSB, otherwise LSB to MSB.
     * @param bigEndian if false, swap incoming bytes to little-endian, otherwise leave them as little-endian.
     * @return {@link #EOS} or the 32bit value, which might be unsigned or 2-complement signed value.
     * @throws IllegalStateException if not in input mode or stream closed
     * @throws IOException
     */
    public final int readInt32(final boolean msbFirst, final boolean bigEndian) throws IllegalStateException, IOException {
        if( 0 == bitCount && msbFirst ) {
            // fast path
            if( outputMode || null == bytes ) {
                throw new IllegalStateException("not in input-mode: "+this);
            }
            final int b1 = bytes.read();
            final int b2 = EOS != b1 ? bytes.read() : EOS;
            final int b3 = EOS != b2 ? bytes.read() : EOS;
            final int b4 = EOS != b3 ? bytes.read() : EOS;
            if( EOS == b4 ) {
                return EOS;
            } else if( bigEndian ) {
                return b1 << 24 | b2 << 16 | b3 << 8 | b4;
            } else {
                return b4 << 24 | b3 << 16 | b2 << 8 | b1;
            }
        } else {
            final int i16a = readBits31(msbFirst, 16);
            final int i16b = EOS != i16a ? readBits31(msbFirst, 16) : EOS;
            if( EOS == i16b ) {
                return EOS;
            } else if( bigEndian ) {
                return i16a << 16 | i16b;
            } else {
                final int b1 = 0xff & ( i16a >>> 8 );
                final int b2 = 0xff &   i16a;
                final int b3 = 0xff & ( i16b >>> 8 );
                final int b4 = 0xff &   i16b;
                return b4 << 24 | b3 << 16 | b2 << 8 | b1;
            }
        }
    }

    /**
     * Return incoming int32 as read via {@link #readBits31(boolean, int)}
     * and swap bytes if !bigEndian.
     * <p>
     * In case the returned value shall be interpreted as <code>uint32_t</code>
     * utilize {@link #toUInt32Long(int)} or {@link #toUInt32Int(int)} for
     * an appropriate conversion.
     * </p>
     * @param bigEndian if false, swap incoming bytes to little-endian, otherwise leave them as little-endian.
     * @return the 32bit value, which might be unsigned or 2-complement signed value.
     * @throws IndexOutOfBoundsException
     */
    public static final int readInt32(final boolean bigEndian, final byte[] bytes, final int offset) throws IndexOutOfBoundsException {
        checkBounds(bytes, offset, 4);
        final int b1 = bytes[offset];
        final int b2 = bytes[offset+1];
        final int b3 = bytes[offset+2];
        final int b4 = bytes[offset+3];
        if( bigEndian ) {
            return b1 << 24 | b2 << 16 | b3 << 8 | b4;
        } else {
            return b4 << 24 | b3 << 16 | b2 << 8 | b1;
        }
    }

    /**
     * Write the given int32 via {@link #writeBits31(boolean, int, int)},
     * while swapping bytes if !bigEndian beforehand.
     * @param msbFirst if true incoming stream bit order is MSB to LSB, otherwise LSB to MSB.
     * @param bigEndian if false, swap given bytes to little-endian, otherwise leave them as little-endian.
     * @return {@link #EOS} or the written 32bit value.
     * @throws IllegalStateException if not in output mode or stream closed
     * @throws IOException
     */
    public final int writeInt32(final boolean msbFirst, final boolean bigEndian, final int int32) throws IllegalStateException, IOException {
        if( 0 == bitCount && msbFirst ) {
            // fast path
            if( !outputMode || null == bytes ) {
                throw new IllegalStateException("not in output-mode: "+this);
            }
            final byte p1 = (byte) ( 0xff & ( int32 >>> 24 ) );
            final byte p2 = (byte) ( 0xff & ( int32 >>> 16 ) );
            final byte p3 = (byte) ( 0xff & ( int32 >>>  8 ) );
            final byte p4 = (byte) ( 0xff &   int32          );
            final byte b1, b2, b3, b4;
            if( bigEndian ) {
                b1 = p1;
                b2 = p2;
                b3 = p3;
                b4 = p4;
            } else {
                b1 = p4;
                b2 = p3;
                b3 = p2;
                b4 = p1;
            }
            if( EOS != bytes.write(b1) ) {
                if( EOS != bytes.write(b2) ) {
                    if( EOS != bytes.write(b3) ) {
                        if( EOS != bytes.write(b4) ) {
                            return int32;
                        }
                    }
                }
            }
            return EOS;
        } else if( bigEndian ) {
            final int hi = 0x0000ffff & ( int32 >>> 16 );
            final int lo = 0x0000ffff &   int32 ;
            if( EOS != writeBits31(msbFirst, 16, hi) ) {
                if( EOS != writeBits31(msbFirst, 16, lo) ) {
                    return int32;
                }
            }
            return EOS;
        } else {
            final int p1 = 0xff & ( int32 >>> 24 );
            final int p2 = 0xff & ( int32 >>> 16 );
            final int p3 = 0xff & ( int32 >>>  8 );
            final int p4 = 0xff &   int32         ;
            if( EOS != writeBits31(msbFirst, 16, p4 << 8 | p3) ) {
                if( EOS != writeBits31(msbFirst, 16, p2 << 8 | p1) ) {
                    return int32;
                }
            }
            return EOS;
        }
    }

    /**
     * Reinterpret the given <code>int32_t</code> value as <code>uint32_t</code>,
     * i.e. perform the following cast to <code>long</code>:
     * <pre>
     *   final long l = 0xffffffffL & int32;
     * </pre>
     */
    public static final long toUInt32Long(final int int32) {
        return 0xffffffffL & int32;
    }

    /**
     * Returns the reinterpreted given <code>int32_t</code> value
     * as <code>uint32_t</code> if &le; {@link Integer#MAX_VALUE}
     * as within an <code>int</code> storage.
     * Otherwise return -1.
     */
    public static final int toUInt32Int(final int int32) {
        return uint32LongToInt(toUInt32Long(int32));
    }

    /**
     * Returns the given <code>uint32_t</code> value <code>long</code>
     * value as <code>int</code> if &le; {@link Integer#MAX_VALUE}.
     * Otherwise return -1.
     */
    public static final int uint32LongToInt(final long uint32) {
        if( Integer.MAX_VALUE >= uint32 ) {
            return (int)uint32;
        } else {
            return -1;
        }
    }

    public String toString() {
        return String.format("Bitstream[%s]", toStringImpl());
    }
    protected String toStringImpl() {
        final String mode;
        final long bpos;
        if( null == bytes ) {
            mode = "closed";
            bpos = -1;
        } else {
            mode = outputMode ? "output" : "input";
            bpos = bytes.position();
        }
        return String.format("%s, pos %d [byteP %d, bitCnt %d], bitbuf %s",
                mode, position(), bpos, bitCount, toHexBinString(bitBuffer, 8));
    }

    private static final String strZeroPadding= "0000000000000000000000000000000000000000000000000000000000000000"; // 64
    public static String toBinString(final int v, final int bitCount) {
        if( 0 == bitCount ) {
            return "";
        }
        final int mask = (int) ( ( 1L << bitCount ) - 1L );
        final String s0 = Integer.toBinaryString( mask & v );
        return strZeroPadding.substring(0, bitCount-s0.length())+s0;
    }
    public static String toHexBinString(final int v, final int bitCount) {
        final int nibbles = 0 == bitCount ? 2 : ( bitCount + 3 ) / 4;
        return String.format("[%0"+nibbles+"X, %s]", v, toBinString(v, bitCount));
    }
    public static void checkBounds(final byte[] sb, final int offset, final int remaining) throws IndexOutOfBoundsException {
        if( offset + remaining > sb.length ) {
            throw new IndexOutOfBoundsException("Buffer of size "+sb.length+" cannot hold offset "+offset+" + remaining "+remaining);
        }
    }
}
