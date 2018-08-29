/*
 *
 *
 *
 *
 * Copyright  2018 APEX Technologies.Co.Ltd. All rights reserved.
 *
 * FileName: ForkBase.scala
 *
 * @author: ruixiao.xiao@chinapex.com: 18-8-28 上午10:53@version: 1.0
 */

package com.apex.core

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, DataInputStream, DataOutputStream}

import com.apex.crypto.Ecdsa.PublicKey
import com.apex.crypto.UInt256
import com.apex.storage.LevelDbStorage

import collection.mutable.{ListBuffer, Map, Seq, SortedMap}
import scala.collection.mutable

class MultiMap[K, V] extends mutable.Iterable[(K, V)] {
  private val container = Map.empty[K, ListBuffer[V]]

  def contains(k: K) = {
    container.contains(k)
  }

  def get(k: K) = {
    container(k)
  }

  def put(k: K, v: V) = {
    if (!container.contains(k)) {
      container.put(k, ListBuffer.empty)
    }
    container(k).append(v)
  }

  def remove(k: K): Option[Seq[V]] = {
    container.remove(k)
  }

  override def head: (K, V) = iterator.next()

  override def iterator: Iterator[(K, V)] = new MultiMapIterator(container)

  class MultiMapIterator(container: Map[K, ListBuffer[V]]) extends Iterator[(K, V)] {
    private val it = container.iterator

    private var it2: Option[Iterator[V]] = None
    private var k: Option[K] = None

    override def hasNext: Boolean = {
      if (it2.isEmpty || !it2.get.hasNext) {
        nextIt
      }

      !it2.isEmpty && it2.get.hasNext
    }

    override def next(): (K, V) = {
      if (!hasNext) throw new NoSuchElementException
      (k.get, it2.get.next())
    }

    private def nextIt = {
      if (it.hasNext) {
        val next = it.next()
        it2 = Some(next._2.iterator)
        k = Some(next._1)
      }
    }
  }

}

object MultiMap {
  def empty[K, V] = new MultiMap[K, V]
}

class SortedMultiMap1[K, V](implicit ord: Ordering[K]) extends Iterable[(K, V)] {
  private val container = SortedMap.empty[K, ListBuffer[V]]

  def contains(k: K) = {
    container.contains(k)
  }

  def get(k: K) = {
    container(k)
  }

  def put(k: K, v: V): Unit = {
    if (!container.contains(k)) {
      container.put(k, ListBuffer.empty[V])
    }
    container(k).append(v)
  }

  def remove(k: K): Option[Seq[V]] = {
    container.remove(k)
  }

  override def head: (K, V) = iterator.next()

  override def iterator: Iterator[(K, V)] = {
    new SortedMultiMapIterator(container)
  }

  class SortedMultiMapIterator(val map: SortedMap[K, ListBuffer[V]]) extends Iterator[(K, V)] {
    private val it = map.iterator

    private var it2: Option[Iterator[V]] = None
    private var k: Option[K] = None

    override def hasNext: Boolean = {
      if (it2.isEmpty || !it2.get.hasNext) {
        nextIt
      }
      !it2.isEmpty && it2.get.hasNext
    }

    override def next(): (K, V) = {
      if (!hasNext) throw new NoSuchElementException
      (k.get, it2.get.next())
    }

    private def nextIt = {
      if (it.hasNext) {
        val next = it.next()
        it2 = Some(next._2.iterator)
        k = Some(next._1)
      }
    }
  }

}

class SortedMultiMap2[K1, K2, V](implicit ord1: Ordering[K1], ord2: Ordering[K2]) extends Iterable[(K1, K2, V)] {
  private val container = SortedMap.empty[K1, SortedMultiMap1[K2, V]]

  def contains(k1: K1, k2: K2) = {
    container.contains(k1) && container(k1).contains(k2)
  }

  def get(k1: K1, k2: K2) = {
    container(k1).get(k2)
  }

  def put(k1: K1, k2: K2, v: V): Unit = {
    if (!container.contains(k1)) {
      container.put(k1, SortedMultiMap.empty[K2, V])
    }
    container(k1).put(k2, v)
  }

  def remove(k1: K1, k2: K2): Option[Seq[V]] = {
    if (container.contains(k1)) {
      container(k1).remove(k2)
    } else {
      None
    }
  }

  override def head: (K1, K2, V) = iterator.next()

  override def iterator: Iterator[(K1, K2, V)] = {
    new SortedMultiMapIterator(container)
  }

  class SortedMultiMapIterator[K1, K2, V](val map: SortedMap[K1, SortedMultiMap1[K2, V]]) extends Iterator[(K1, K2, V)] {
    private val it = map.iterator

    private var it2: Option[Iterator[(K2, V)]] = None
    private var k1: Option[K1] = None

    override def hasNext: Boolean = {
      if (it2.isEmpty || !it2.get.hasNext) {
        nextIt
      }
      !it2.isEmpty && it2.get.hasNext
    }

    override def next(): (K1, K2, V) = {
      if (!hasNext) throw new NoSuchElementException
      val next = it2.get.next()
      (k1.get, next._1, next._2)
    }

    private def nextIt = {
      if (it.hasNext) {
        val next = it.next()
        it2 = Some(next._2.iterator)
        k1 = Some(next._1)
      }
    }
  }

}

object SortedMultiMap {
  def empty[A, B]()(implicit ord: Ordering[A]): SortedMultiMap1[A, B] = new SortedMultiMap1[A, B]

  def empty[A, B, C]()(implicit ord1: Ordering[A], ord2: Ordering[B]): SortedMultiMap2[A, B, C] = new SortedMultiMap2[A, B, C]
}

case class ForkItem(block: BlockHeader, master: Boolean, lastProducerHeight: Map[PublicKey, Int]) {
  private var _confirmedHeight: Int = -1

  def confirmedHeight: Int = {
    if (_confirmedHeight == -1) {
      val index = lastProducerHeight.size * 2 / 3
      _confirmedHeight = lastProducerHeight.values.toSeq.reverse(index)
    }
    _confirmedHeight
  }

  def toBytes: Array[Byte] = {
    import com.apex.common.Serializable._
    val bs = new ByteArrayOutputStream()
    val os = new DataOutputStream(bs)
    os.write(block)
    os.writeBoolean(master)
    os.writeVarInt(lastProducerHeight.size)
    lastProducerHeight.foreach(p => {
      os.writeByteArray(p._1.toBin)
      os.writeVarInt(p._2)
    })
    bs.toByteArray
  }
}

object ForkItem {
  def fromBytes(bytes: Array[Byte]): ForkItem = {
    import com.apex.common.Serializable._
    val bs = new ByteArrayInputStream(bytes)
    val is = new DataInputStream(bs)
    val header = is.readObj(BlockHeader.deserialize)
    val master = is.readBoolean
    val lastProducerHeight = Map.empty[PublicKey, Int]
    for (_ <- 1 to is.readVarInt) {
      lastProducerHeight += PublicKey(is.readByteArray) -> is.readVarInt
    }
    ForkItem(header, master, lastProducerHeight)
  }
}

class ForkBase(dir: String) {
  private var _head: Option[ForkItem] = None

  val indexById = Map.empty[UInt256, ForkItem]
  val indexByPrev = MultiMap.empty[UInt256, UInt256]
  val indexByHeight = SortedMultiMap.empty[Int, Boolean, UInt256]()(implicitly[Ordering[Int]], implicitly[Ordering[Boolean]].reverse)
  val indexByConfirmedHeight = SortedMultiMap.empty[Int, Int, UInt256]()(implicitly[Ordering[Int]].reverse, implicitly[Ordering[Int]].reverse)

  val db = LevelDbStorage.open(dir)

  db.scan((_, v) => {
    val item = ForkItem.fromBytes(v)
    createIndex(item)
  })

  def head(): Option[ForkItem] = {
    _head
  }

  def add(item: ForkItem): Boolean = {
    if (indexById.contains(item.block.id)) {
      false
    } else {
      if (!indexById.contains(item.block.prevBlock)) {
        false
      } else {
        insert(item)
        val id = indexByConfirmedHeight.head._3
        val headItem = indexById(id)
        _head = Some(headItem)
        removeConfirmed(headItem.confirmedHeight)
        true
      }
    }
  }

  private def removeConfirmed(height: Int) = {
    val items = Seq.empty[ForkItem]
    for (p <- indexByHeight if p._1 < height) {
      if (indexById.contains(p._3)) {
        items :+ indexById(p._3)
      }
    }
    items.foreach(item => {
      if (item.master) {
        db.delete(item.block.id.toBytes)
      } else {
        removeFork(item.block.id)
      }
    })
  }

  private def removeFork(id: UInt256) = {
    val items = indexByPrev.get(id)
      .map(indexById.get)
      .filterNot(_.isEmpty)
      .map(_.get)

    db.batchWrite(batch => {
      items.map(_.block.id.toBytes).foreach(batch.delete)
      items.foreach(deleteIndex)
    })
  }

  private def insert(item: ForkItem): Boolean = {
    if (db.set(item.block.id.toBytes, item.toBytes)) {
      createIndex(item)
      true
    } else {

      false
    }
  }

  private def createIndex(item: ForkItem): Unit = {
    val blk = item.block
    indexById.put(blk.id, item)
    indexByPrev.put(blk.prevBlock, blk.id)
    indexByHeight.put(blk.index, item.master, blk.id)
    indexByConfirmedHeight.put(item.confirmedHeight, blk.index, blk.id)
  }

  private def deleteIndex(item: ForkItem): Unit = {
    val blk = item.block
    indexById.remove(blk.id)
    indexByPrev.remove(blk.prevBlock)
    indexByHeight.remove(blk.index, item.master)
    indexByConfirmedHeight.remove(item.confirmedHeight, blk.index)
  }
}
