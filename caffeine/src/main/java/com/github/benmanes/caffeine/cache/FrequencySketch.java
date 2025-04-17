/*
 * Copyright 2015 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.cache;

import static com.github.benmanes.caffeine.cache.Caffeine.requireArgument;

import org.checkerframework.checker.index.qual.NonNegative;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * A probabilistic multiset for estimating the popularity of an element within a time window. The
 * maximum frequency of an element is limited to 15 (4-bits) and an aging process periodically
 * halves the popularity of all elements.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class FrequencySketch<E> {

  /*
   * This class maintains a 4-bit CountMinSketch [1] with periodic aging to provide the popularity
   * history for the TinyLfu admission policy [2]. The time and space efficiency of the sketch
   * allows it to cheaply estimate the frequency of an entry in a stream of cache access events.
   *
   * The counter matrix is represented as a single dimensional array holding 16 counters per slot. A
   * fixed depth of four balances the accuracy and cost, resulting in a width of four times the
   * length of the array. To retain an accurate estimation the array's length equals the maximum
   * number of entries in the cache, increased to the closest power-of-two to exploit more efficient
   * bit masking. This configuration results in a confidence of 93.75% and error bound of e / width.
   *
   * The frequency of all entries is aged periodically using a sampling window based on the maximum
   * number of entries in the cache. This is referred to as the reset operation by TinyLfu and keeps
   * the sketch fresh by dividing all counters by two and subtracting based on the number of odd
   * counters found. The O(n) cost of aging is amortized, ideal for hardware prefetching, and uses
   * inexpensive bit manipulations per array location.
   *
   * [1] An Improved Data Stream Summary: The Count-Min Sketch and its Applications
   * http://dimacs.rutgers.edu/~graham/pubs/papers/cm-full.pdf
   * [2] TinyLFU: A Highly Efficient Cache Admission Policy
   * https://dl.acm.org/citation.cfm?id=3149371
   */

  static final long[] SEED = { // A mixture of seeds from FNV-1a, CityHash, and Murmur3
      0xc3a5c85c97cb3127L, 0xb492b66fbe98f273L, 0x9ae16a3b2f90404fL, 0xcbf29ce484222325L};
  static final long RESET_MASK = 0x7777777777777777L;
  static final long ONE_MASK = 0x1111111111111111L;

  int sampleSize;
  int tableMask;
  long[] table;
  int size;

  /**
   * Creates a lazily initialized frequency sketch, requiring {@link #ensureCapacity} be called
   * when the maximum size of the cache has been determined.
   */
  @SuppressWarnings("NullAway.Init")
  public FrequencySketch() {}

  /**
   * Initializes and increases the capacity of this <tt>FrequencySketch</tt> instance, if necessary,
   * to ensure that it can accurately estimate the popularity of elements given the maximum size of
   * the cache. This operation forgets all previous counts when resizing.
   *
   * @param maximumSize the maximum size of the cache
   */
  public void ensureCapacity(@NonNegative long maximumSize) {
    requireArgument(maximumSize >= 0);
    int maximum = (int) Math.min(maximumSize, Integer.MAX_VALUE >>> 1);
    if ((table != null) && (table.length >= maximum)) {
      return;
    }

    table = new long[(maximum == 0) ? 1 : Caffeine.ceilingPowerOfTwo(maximum)];
    tableMask = Math.max(0, table.length - 1);
    sampleSize = (maximumSize == 0) ? 10 : (10 * maximum);
    if (sampleSize <= 0) {
      sampleSize = Integer.MAX_VALUE;
    }
    size = 0;
  }

  /**
   * Returns if the sketch has not yet been initialized, requiring that {@link #ensureCapacity} is
   * called before it begins to track frequencies.
   */
  public boolean isNotInitialized() {
    return (table == null);
  }

  /**
   * Returns the estimated number of occurrences of an element, up to the maximum (15).
   *
   * @param e the element to count occurrences of
   * @return the estimated number of occurrences of the element; possibly zero but never negative
   */
  @NonNegative
  public int frequency(@NonNull E e) {
    if (isNotInitialized()) {
      return 0;
    }

    int hash = spread(e.hashCode());
    int start = (hash & 3) << 2; //取最后两位，并左移2位
    int frequency = Integer.MAX_VALUE;
    for (int i = 0; i < 4; i++) { //分别计算hash找到对应的long，并在该long对应的4字节上读取数据
      int index = indexOf(hash, i); //找到对应的long
      int count = (int) ((table[index] >>> ((start + i) << 2)) & 0xfL); //在对应的long的对应4自己上读取数据
      frequency = Math.min(frequency, count);
    }
    return frequency;
  }

  /**
   * Increments the popularity of the element if it does not exceed the maximum (15). The popularity
   * of all elements will be periodically down sampled when the observed events exceeds a threshold.
   * This process provides a frequency aging to allow expired long term entries to fade away.
   *
   * @param e the element to add
   */
  public void increment(@NonNull E e) {
    if (isNotInitialized()) {
      return;
    }

    int hash = spread(e.hashCode()); //计算Hash
    int start = (hash & 3) << 2; //映射到long的16个槽位上。末尾两位为0，后面用来放不同的hash

    // Loop unrolling improves throughput by 5m ops/s
    int index0 = indexOf(hash, 0);//计算hash0映射的位置
    int index1 = indexOf(hash, 1);//计算hash1映射的位置
    int index2 = indexOf(hash, 2);//计算hash2映射的位置
    int index3 = indexOf(hash, 3);//计算hash3映射的位置

    boolean added = incrementAt(index0, start); //hash0，放到long的第1个4字节
    added |= incrementAt(index1, start + 1); //hash1，放到long的第2个4字节
    added |= incrementAt(index2, start + 2); //hash2，放到long的第3个4字节
    added |= incrementAt(index3, start + 3); //hash3，放到long的第4个4字节

    if (added && (++size == sampleSize)) {
      reset();
    }
  }

  /**
   * Increments the specified counter by 1 if it is not already at the maximum value (15).
   *
   * @param i the table index (16 counters) //放到table的哪个long上
   * @param j the counter to increment //放到对应long的第几个4字节上
   * @return if incremented
   */
  boolean incrementAt(int i, int j) {
    int offset = j << 2; //*4找到long中对应4字节的起始位置
    long mask = (0xfL << offset); //将15（1111）移动到对应4字节的位置形成mask
    if ((table[i] & mask) != mask) { //如果该4字节的数据值为最大值15了，则不继续加
      table[i] += (1L << offset); //将1移动到该4字节的其实位置，并加上原来的数据，实现该4字节数据加1的操作
      return true;
    }
    return false;
  }

  /** Reduces every counter by half of its original value. */
  //TinyLFU论文的3.3有数学证明
  void reset() {
    int count = 0;
    for (int i = 0; i < table.length; i++) {
      count += Long.bitCount(table[i] & ONE_MASK); //统计每个4字节最低位为1的数量
      table[i] = (table[i] >>> 1) & RESET_MASK;  //将每个4字节的数据除以2
    }
    size = (size >>> 1) - (count >>> 2); //大致思想是将size除以2，并减去4字节低位1的数量/4
  }

  /**
   * Returns the table index for the counter at the specified depth.
   *
   * @param item the element's hash
   * @param i the counter depth
   * @return the table index
   */
  int indexOf(int item, int i) {
    long hash = (item + SEED[i]) * SEED[i];
    hash += (hash >>> 32);
    return ((int) hash) & tableMask;
  }

  /**
   * Applies a supplemental hash function to a given hashCode, which defends against poor quality
   * hash functions.
   */
  int spread(int x) {
    x = ((x >>> 16) ^ x) * 0x45d9f3b;
    x = ((x >>> 16) ^ x) * 0x45d9f3b;
    return (x >>> 16) ^ x;
  }
}
