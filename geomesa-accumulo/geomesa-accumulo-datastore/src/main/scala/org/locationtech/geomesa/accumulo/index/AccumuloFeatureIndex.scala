/***********************************************************************
* Copyright (c) 2013-2016 Commonwealth Computer Research, Inc.
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the Apache License, Version 2.0
* which accompanies this distribution and is available at
* http://www.opensource.org/licenses/apache2.0.php.
*************************************************************************/

package org.locationtech.geomesa.accumulo.index

import java.util.Map.Entry

import org.apache.accumulo.core.data.{Key, Mutation, Value}
import org.apache.hadoop.io.Text
import org.geotools.filter.identity.FeatureIdImpl
import org.locationtech.geomesa.accumulo.data._
import org.locationtech.geomesa.accumulo.index.AccumuloFeatureIndex.AccumuloFeatureIndex
import org.locationtech.geomesa.accumulo.index.attribute.AttributeIndex
import org.locationtech.geomesa.accumulo.index.z2.XZ2Index
import org.locationtech.geomesa.accumulo.index.z3.XZ3Index
// noinspection ScalaDeprecation
import org.locationtech.geomesa.accumulo.index.geohash.GeoHashIndex
import org.locationtech.geomesa.accumulo.index.id.RecordIndex
import org.locationtech.geomesa.accumulo.index.z2.Z2Index
import org.locationtech.geomesa.accumulo.index.z3.Z3Index
import org.locationtech.geomesa.accumulo.util.GeoMesaBatchWriterConfig
import org.locationtech.geomesa.features.SerializationOption.SerializationOptions
import org.locationtech.geomesa.features.{SerializationType, SimpleFeatureDeserializers}
import org.locationtech.geomesa.index.api._
import org.locationtech.geomesa.security.SecurityUtils
import org.opengis.feature.simple.{SimpleFeature, SimpleFeatureType}

object AccumuloFeatureIndex {

  type AccumuloFeatureIndex = GeoMesaFeatureIndex[AccumuloDataStore, WritableFeature, Seq[Mutation], QueryPlan]
  type AccumuloFilterPlan = FilterPlan[AccumuloDataStore, WritableFeature, Seq[Mutation], QueryPlan]
  type AccumuloFilterStrategy = FilterStrategy[AccumuloDataStore, WritableFeature, Seq[Mutation], QueryPlan]

  // noinspection ScalaDeprecation
  // note: keep in priority order for running full table scans
  val AllIndices: Seq[AccumuloWritableIndex] =
    Seq(Z3Index, XZ3Index, Z2Index, XZ2Index, RecordIndex, AttributeIndex, GeoHashIndex)

  def indices(sft: SimpleFeatureType): Seq[AccumuloWritableIndex] = AllIndices.filter(_.supports(sft))

  object Schemes {
    val Z3TableScheme: List[String] = List(AttributeIndex, RecordIndex, Z3Index, XZ3Index).map(_.name)
    val Z2TableScheme: List[String] = List(AttributeIndex, RecordIndex, Z2Index, XZ2Index).map(_.name)
  }
}

trait AccumuloWritableIndex extends AccumuloFeatureIndex {

  override def removeAll(sft: SimpleFeatureType, ops: AccumuloDataStore): Unit = {
    import org.apache.accumulo.core.data.{Range => aRange}
    import org.locationtech.geomesa.utils.geotools.RichSimpleFeatureType.RichSimpleFeatureType

    import scala.collection.JavaConversions._

    val table = ops.getTableName(sft.getTypeName, this)
    if (ops.tableOps.exists(table)) {
      val auths = ops.authProvider.getAuthorizations
      val config = GeoMesaBatchWriterConfig().setMaxWriteThreads(ops.config.writeThreads)
      val prefix = new Text(sft.getTableSharingPrefix)
      val deleter = ops.connector.createBatchDeleter(table, auths, ops.config.queryThreads, config)
      try {
        deleter.setRanges(Seq(new aRange(prefix, true, aRange.followingPrefix(prefix), false)))
        deleter.delete()
      } finally {
        deleter.close()
      }
    }
  }

  /**
    * Retrieve an ID from a row. All indices are assumed to encode the feature ID into the row key
    *
    * @param sft simple feature type
    * @return a function to retrieve an ID from a row
    */
  def getIdFromRow(sft: SimpleFeatureType): (Text) => String

  /**
    * Turns accumulo results into simple features
    *
    * @param sft simple feature type
    * @param returnSft return simple feature type (transform, etc)
    * @return
    */
  def entriesToFeatures(sft: SimpleFeatureType, returnSft: SimpleFeatureType): (Entry[Key, Value]) => SimpleFeature = {
    import org.locationtech.geomesa.utils.geotools.RichSimpleFeatureType.RichSimpleFeatureType
    // Perform a projecting decode of the simple feature
    if (sft.getSchemaVersion < 9) {
      val deserializer = SimpleFeatureDeserializers(returnSft, SerializationType.KRYO)
      (kv: Entry[Key, Value]) => {
        val sf = deserializer.deserialize(kv.getValue.get)
        AccumuloWritableIndex.applyVisibility(sf, kv.getKey)
        sf
      }
    } else {
      val getId = getIdFromRow(sft)
      val deserializer = SimpleFeatureDeserializers(returnSft, SerializationType.KRYO, SerializationOptions.withoutId)
      (kv: Entry[Key, Value]) => {
        val sf = deserializer.deserialize(kv.getValue.get)
        sf.getIdentifier.asInstanceOf[FeatureIdImpl].setID(getId(kv.getKey.getRow))
        AccumuloWritableIndex.applyVisibility(sf, kv.getKey)
        sf
      }
    }
  }
}

object AccumuloWritableIndex {

  val DefaultNumSplits = 4 // can't be more than Byte.MaxValue (127)
  val DefaultSplitArrays = (0 until DefaultNumSplits).map(_.toByte).toArray.map(Array(_)).toSeq

  val NullByte = Array(0.toByte)
  val EmptyBytes = Array.empty[Byte]

  val FullColumnFamily      = new Text("F")
  val IndexColumnFamily     = new Text("I")
  val BinColumnFamily       = new Text("B")
  val AttributeColumnFamily = new Text("A")

  val EmptyColumnQualifier  = new Text()

  val EmptyValue = new Value(EmptyBytes)

  def applyVisibility(sf: SimpleFeature, key: Key): Unit = {
    val visibility = key.getColumnVisibility
    if (visibility.getLength > 0) {
      SecurityUtils.setFeatureVisibility(sf, visibility.toString)
    }
  }
}