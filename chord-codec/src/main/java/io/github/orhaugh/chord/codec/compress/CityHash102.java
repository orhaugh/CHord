/*
 * Copyright 2026 Ross Haugh
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.orhaugh.chord.codec.compress;

/**
 * CityHash128 exactly as ClickHouse computes it for compressed frame checksums: version 1.0.2,
 * little endian, with the version specific seeding that later CityHash releases changed.
 *
 * <p>This class is a Java port of CityHash version 1.0.2 by Geoff Pike and Jyrki Alakuijala
 * (Copyright 2011 Google, Inc., MIT licensed; see the NOTICE file), as vendored by ClickHouse in
 * {@code contrib/cityhash102}. ClickHouse froze this exact version for its network and on disk
 * checksums; later CityHash versions produce different values and must not be substituted.
 */
public final class CityHash102 {

  private static final long K0 = 0xc3a5c85c97cb3127L;
  private static final long K1 = 0xb492b66fbe98f273L;
  private static final long K2 = 0x9ae16a3b2f90404fL;
  private static final long K3 = 0xc949d7c7509e6557L;

  private CityHash102() {}

  /**
   * Computes the 128 bit CityHash 1.0.2 of a byte range.
   *
   * @param data the input
   * @param offset first byte of the range
   * @param length length of the range
   * @return the hash as {@code [low64, high64]}, matching ClickHouse's uint128 order on the wire
   */
  public static long[] cityHash128(byte[] data, int offset, int length) {
    if (length >= 16) {
      return cityHash128WithSeed(
          data, offset + 16, length - 16, fetch64(data, offset) ^ K3, fetch64(data, offset + 8));
    }
    if (length >= 8) {
      return cityHash128WithSeed(
          data,
          offset,
          0,
          fetch64(data, offset) ^ (length * K0),
          fetch64(data, offset + length - 8) ^ K1);
    }
    return cityHash128WithSeed(data, offset, length, K0, K1);
  }

  private static long[] cityHash128WithSeed(
      byte[] data, int offset, int length, long seedLow, long seedHigh) {
    if (length < 128) {
      return cityMurmur(data, offset, length, seedLow, seedHigh);
    }

    long x = seedLow;
    long y = seedHigh;
    long z = length * K1;
    long vFirst = Long.rotateRight(y ^ K1, 49) * K1 + fetch64(data, offset);
    long vSecond = Long.rotateRight(vFirst, 42) * K1 + fetch64(data, offset + 8);
    long wFirst = Long.rotateRight(y + z, 35) * K1 + x;
    long wSecond = Long.rotateRight(x + fetch64(data, offset + 88), 53) * K1;

    int s = offset;
    int remaining = length;
    do {
      for (int iteration = 0; iteration < 2; iteration++) {
        x = Long.rotateRight(x + y + vFirst + fetch64(data, s + 16), 37) * K1;
        y = Long.rotateRight(y + vSecond + fetch64(data, s + 48), 42) * K1;
        x ^= wSecond;
        y ^= vFirst;
        z = Long.rotateRight(z ^ wFirst, 33);
        long newVFirst = weakFirst(data, s, vSecond * K1, x + wFirst);
        long newVSecond = weakSecond(data, s, vSecond * K1, x + wFirst);
        long newWFirst = weakFirst(data, s + 32, z + wSecond, y);
        long newWSecond = weakSecond(data, s + 32, z + wSecond, y);
        vFirst = newVFirst;
        vSecond = newVSecond;
        wFirst = newWFirst;
        wSecond = newWSecond;
        long swap = z;
        z = x;
        x = swap;
        s += 64;
      }
      remaining -= 128;
    } while (remaining >= 128);

    y += Long.rotateRight(wFirst, 37) * K0 + z;
    x += Long.rotateRight(vFirst + z, 49) * K0;
    for (int tailDone = 0; tailDone < remaining; ) {
      tailDone += 32;
      y = Long.rotateRight(y - x, 42) * K0 + vSecond;
      wFirst += fetch64(data, s + remaining - tailDone + 16);
      x = Long.rotateRight(x, 49) * K0 + wFirst;
      wFirst += vFirst;
      long newVFirst = weakFirst(data, s + remaining - tailDone, vFirst, vSecond);
      long newVSecond = weakSecond(data, s + remaining - tailDone, vFirst, vSecond);
      vFirst = newVFirst;
      vSecond = newVSecond;
    }
    x = hashLen16(x, vFirst);
    y = hashLen16(y, wFirst);
    return new long[] {hashLen16(x + vSecond, wSecond) + y, hashLen16(x + wSecond, y + vSecond)};
  }

  private static long[] cityMurmur(
      byte[] data, int offset, int length, long seedLow, long seedHigh) {
    long a = seedLow;
    long b = seedHigh;
    long c;
    long d;
    int l = length - 16;
    if (l <= 0) {
      a = shiftMix(a * K1) * K1;
      c = b * K1 + hashLen0to16(data, offset, length);
      d = shiftMix(a + (length >= 8 ? fetch64(data, offset) : c));
    } else {
      c = hashLen16(fetch64(data, offset + length - 8) + K1, a);
      d = hashLen16(b + length, c + fetch64(data, offset + length - 16));
      a += d;
      int s = offset;
      do {
        a ^= shiftMix(fetch64(data, s) * K1) * K1;
        a *= K1;
        b ^= a;
        c ^= shiftMix(fetch64(data, s + 8) * K1) * K1;
        c *= K1;
        d ^= c;
        s += 16;
        l -= 16;
      } while (l > 0);
    }
    a = hashLen16(a, c);
    b = hashLen16(d, b);
    return new long[] {a ^ b, hashLen16(b, a)};
  }

  private static long weakFirst(byte[] data, int s, long a, long b) {
    return weak(data, s, a, b, true);
  }

  private static long weakSecond(byte[] data, int s, long a, long b) {
    return weak(data, s, a, b, false);
  }

  private static long weak(byte[] data, int s, long a, long b, boolean first) {
    long w = fetch64(data, s);
    long x = fetch64(data, s + 8);
    long y = fetch64(data, s + 16);
    long z = fetch64(data, s + 24);
    a += w;
    b = Long.rotateRight(b + a + z, 21);
    long c = a;
    a += x;
    a += y;
    b += Long.rotateRight(a, 44);
    return first ? a + z : b + c;
  }

  private static long hashLen0to16(byte[] data, int offset, int length) {
    if (length > 8) {
      long a = fetch64(data, offset);
      long b = fetch64(data, offset + length - 8);
      return hashLen16(a, rotateByAtLeast1(b + length, length)) ^ b;
    }
    if (length >= 4) {
      long a = fetch32(data, offset);
      return hashLen16(length + (a << 3), fetch32(data, offset + length - 4));
    }
    if (length > 0) {
      int a = data[offset] & 0xFF;
      int b = data[offset + (length >> 1)] & 0xFF;
      int c = data[offset + length - 1] & 0xFF;
      int y = a + (b << 8);
      int z = length + (c << 2);
      return shiftMix((y * K2) ^ (z * K3)) * K2;
    }
    return K2;
  }

  private static long hashLen16(long u, long v) {
    long kMul = 0x9ddfea08eb382d69L;
    long a = (u ^ v) * kMul;
    a ^= a >>> 47;
    long b = (v ^ a) * kMul;
    b ^= b >>> 47;
    return b * kMul;
  }

  private static long shiftMix(long value) {
    return value ^ (value >>> 47);
  }

  private static long rotateByAtLeast1(long value, int shift) {
    return (value >>> shift) | (value << (64 - shift));
  }

  private static long fetch64(byte[] data, int index) {
    return (data[index] & 0xFFL)
        | (data[index + 1] & 0xFFL) << 8
        | (data[index + 2] & 0xFFL) << 16
        | (data[index + 3] & 0xFFL) << 24
        | (data[index + 4] & 0xFFL) << 32
        | (data[index + 5] & 0xFFL) << 40
        | (data[index + 6] & 0xFFL) << 48
        | (data[index + 7] & 0xFFL) << 56;
  }

  private static long fetch32(byte[] data, int index) {
    return (data[index] & 0xFFL)
        | (data[index + 1] & 0xFFL) << 8
        | (data[index + 2] & 0xFFL) << 16
        | (data[index + 3] & 0xFFL) << 24;
  }
}
