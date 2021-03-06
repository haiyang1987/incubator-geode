/*=========================================================================
 * Copyright (c) 2010-2014 Pivotal Software, Inc. All Rights Reserved.
 * This product is protected by U.S. and international copyright
 * and intellectual property laws. Pivotal products are covered by
 * one or more patents listed at http://www.pivotal.io/patents.
 *=========================================================================
 */
package com.gemstone.gemfire.cache.query.internal.index;

import java.util.Collection;
import java.util.Iterator;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.RegionAttributes;
import com.gemstone.gemfire.cache.query.IndexStatistics;
import com.gemstone.gemfire.internal.cache.BucketRegion;
import com.gemstone.gemfire.internal.cache.RegionEntry;

public class MapRangeIndex extends AbstractMapIndex
{
  protected final RegionEntryToValuesMap entryToMapKeysMap;

  MapRangeIndex(String indexName, Region region, String fromClause,
      String indexedExpression, String projectionAttributes,
      String origFromClause, String origIndxExpr, String[] defintions,
      boolean isAllKeys, String[] multiIndexingKeysPattern, Object[] mapKeys, IndexStatistics stats) {
    super(indexName, region, fromClause, indexedExpression,
        projectionAttributes, origFromClause, origIndxExpr, defintions, 
        isAllKeys, multiIndexingKeysPattern, mapKeys, stats);
    RegionAttributes ra = region.getAttributes();
    this.entryToMapKeysMap = new RegionEntryToValuesMap(new java.util.concurrent.ConcurrentHashMap(ra.getInitialCapacity(),ra.getLoadFactor(), ra.getConcurrencyLevel()),
        true /* user target list as the map keys will be unique*/);
  }
  
  @Override
  void recreateIndexData() throws IMQException
  {
    /*
     * Asif : Mark the data maps to null & call the initialization code of index
     */
    // TODO:Asif : The statistics data needs to be modified appropriately
    // for the clear operation
    this.mapKeyToValueIndex.clear();
    this.entryToMapKeysMap.clear();
    this.initializeIndex(true);
  }
  
  @Override
  public boolean containsEntry(RegionEntry entry)
  {
    // TODO:Asif: take care of null mapped entries
    /*
     * return (this.entryToValuesMap.containsEntry(entry) ||
     * this.nullMappedEntries.containsEntry(entry) ||
     * this.undefinedMappedEntries .containsEntry(entry));
     */
    return this.entryToMapKeysMap.containsEntry(entry);
  }
  
  @Override
  void addMapping(RegionEntry entry) throws IMQException
  {
    this.evaluator.evaluate(entry, true);
    addSavedMappings(entry);
    clearCurrState();
  }
  
  public void clearCurrState() {
    for (Object rangeInd : this.mapKeyToValueIndex.values()) {
      ((RangeIndex)rangeInd).clearCurrState();
    }
  }

  private void addSavedMappings(RegionEntry entry) throws IMQException {
    for (Object rangeInd : this.mapKeyToValueIndex.values()) {
      ((RangeIndex)rangeInd).addSavedMappings(entry);
    }
  }

  @Override
  protected void removeMapping(RegionEntry entry, int opCode) throws IMQException
  {
    // this implementation has a reverse map, so it doesn't handle
    // BEFORE_UPDATE_OP
    if (opCode == BEFORE_UPDATE_OP) {
      return;
    }

    Object values = this.entryToMapKeysMap.remove(entry);
    //Values in reverse coould be null if map in region value does not
    //contain any key which matches to index expression keys.
    if (values == null ) {
      return;
    }
    if (values instanceof Collection) {
      Iterator valuesIter = ((Collection)values).iterator();
      while (valuesIter.hasNext()) {
        Object key = valuesIter.next();
        RangeIndex ri = (RangeIndex)this.mapKeyToValueIndex.get(key);
        long start = System.nanoTime();
        this.internalIndexStats.incUpdatesInProgress(1);
        ri.removeMapping(entry, opCode);
        this.internalIndexStats.incUpdatesInProgress(-1);
        long end = - start;
        this.internalIndexStats.incUpdateTime(end);
      }
    }
    else {
      RangeIndex ri = (RangeIndex)this.mapKeyToValueIndex.get(values);
      long start = System.nanoTime();
      this.internalIndexStats.incUpdatesInProgress(1);
      ri.removeMapping(entry, opCode);
      this.internalIndexStats.incUpdatesInProgress(-1);
      long end = System.nanoTime() - start;
      this.internalIndexStats.incUpdateTime(end);
    }
  }

  protected void doIndexAddition(Object mapKey, Object indexKey, Object value,
      RegionEntry entry) throws IMQException
  {
    boolean isPr = this.region instanceof BucketRegion;
    // Get RangeIndex for it or create it if absent
    RangeIndex rg = (RangeIndex) this.mapKeyToValueIndex.get(mapKey);
    if (rg == null) {
      // use previously created MapRangeIndexStatistics
      IndexStatistics stats = this.internalIndexStats;
      PartitionedIndex prIndex = null;
      if (isPr) {
        prIndex = (PartitionedIndex) this.getPRIndex();
        prIndex.incNumMapKeysStats(mapKey);
      }
      rg = new RangeIndex(indexName+"-"+mapKey, region, fromClause, indexedExpression,
          projectionAttributes, this.originalFromClause,
          this.originalIndexedExpression, this.canonicalizedDefinitions, stats);
      //Shobhit: We need evaluator to verify RegionEntry and IndexEntry inconsistency.
      rg.evaluator = this.evaluator;
      this.mapKeyToValueIndex.put(mapKey, rg);
      if(!isPr) {
        this.internalIndexStats.incNumMapIndexKeys(1);
      }
    }
    this.internalIndexStats.incUpdatesInProgress(1);
    long start = System.nanoTime();
    rg.addMapping(indexKey, value, entry);
    //This call is skipped when addMapping is called from MapRangeIndex
    //rg.internalIndexStats.incNumUpdates();
    this.internalIndexStats.incUpdatesInProgress(-1);
    long end = System.nanoTime() - start;
    this.internalIndexStats.incUpdateTime(end);
    this.entryToMapKeysMap.add(entry, mapKey);
  }

  protected void saveIndexAddition(Object mapKey, Object indexKey, Object value,
      RegionEntry entry) throws IMQException
  {
    boolean isPr = this.region instanceof BucketRegion;
    // Get RangeIndex for it or create it if absent
    RangeIndex rg = (RangeIndex) this.mapKeyToValueIndex.get(mapKey);
    if (rg == null) {
      // use previously created MapRangeIndexStatistics
      IndexStatistics stats = this.internalIndexStats;
      PartitionedIndex prIndex = null;
      if (isPr) {
        prIndex = (PartitionedIndex) this.getPRIndex();
        prIndex.incNumMapKeysStats(mapKey);
      }
      rg = new RangeIndex(indexName+"-"+mapKey, region, fromClause, indexedExpression,
          projectionAttributes, this.originalFromClause,
          this.originalIndexedExpression, this.canonicalizedDefinitions, stats);
      rg.evaluator = this.evaluator;
      this.mapKeyToValueIndex.put(mapKey, rg);
      if(!isPr) {
        this.internalIndexStats.incNumMapIndexKeys(1);
      }
    }
    //rg.internalIndexStats.incUpdatesInProgress(1);
    long start = System.nanoTime();
    rg.saveMapping(indexKey, value, entry);
    //This call is skipped when addMapping is called from MapRangeIndex
    //rg.internalIndexStats.incNumUpdates();
    this.internalIndexStats.incUpdatesInProgress(-1);
    long end = System.nanoTime() - start;
    this.internalIndexStats.incUpdateTime(end);
    this.entryToMapKeysMap.add(entry, mapKey);
  }
}
