/*=========================================================================
 * Copyright (c) 2002-2014 Pivotal Software, Inc. All Rights Reserved.
 * This product is protected by U.S. and international copyright
 * and intellectual property laws. Pivotal products are covered by
 * more patents listed at http://www.pivotal.io/patents.
 *=========================================================================
 */
package com.gemstone.gemfire.internal.cache.persistence;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Set;

import com.gemstone.gemfire.CancelException;
import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.SystemFailure;
import com.gemstone.gemfire.cache.Cache;
import com.gemstone.gemfire.cache.CacheFactory;
import com.gemstone.gemfire.cache.RegionDestroyedException;
import com.gemstone.gemfire.distributed.internal.DM;
import com.gemstone.gemfire.distributed.internal.DistributionManager;
import com.gemstone.gemfire.distributed.internal.MessageWithReply;
import com.gemstone.gemfire.distributed.internal.PooledDistributionMessage;
import com.gemstone.gemfire.distributed.internal.ReplyException;
import com.gemstone.gemfire.distributed.internal.ReplyMessage;
import com.gemstone.gemfire.distributed.internal.ReplyProcessor21;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.internal.cache.BucketPersistenceAdvisor;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.gemstone.gemfire.internal.cache.ProxyBucketRegion;

/**
 * @author dsmith
 *
 */
public class MembershipFlushRequest extends
  PooledDistributionMessage implements MessageWithReply {

  private String regionPath;
  private int processorId;

  public MembershipFlushRequest() {
  }
  
  public MembershipFlushRequest(String regionPath, int processorId) {
    this.regionPath = regionPath;
    this.processorId = processorId;
  }

  public static void send(
      Set<InternalDistributedMember> recipients, DM dm, String regionPath) throws ReplyException {
    ReplyProcessor21 processor = new ReplyProcessor21(dm, recipients);
    MembershipFlushRequest msg = new MembershipFlushRequest(regionPath, processor.getProcessorId());
    msg.setRecipients(recipients);
    dm.putOutgoing(msg);
    processor.waitForRepliesUninterruptibly();
  }
  

  @Override
  protected void process(DistributionManager dm) {
    int initLevel = LocalRegion.ANY_INIT;
    int oldLevel = LocalRegion.setThreadInitLevelRequirement(initLevel);

    ReplyException exception = null;
    try {
      // get the region from the path, but do NOT wait on initialization,
      // otherwise we could have a distributed deadlock

      Cache cache = CacheFactory.getInstance(dm.getSystem());
      PartitionedRegion region = (PartitionedRegion) cache.getRegion(this.regionPath);
      if(region != null && region.getRegionAdvisor().isInitialized()) {
        ProxyBucketRegion[] proxyBuckets = region.getRegionAdvisor().getProxyBucketArray();
        // buckets are null if initPRInternals is still not complete
        if (proxyBuckets != null) {
          for (ProxyBucketRegion bucket : proxyBuckets) {
            final BucketPersistenceAdvisor persistenceAdvisor = bucket
                .getPersistenceAdvisor();
            if (persistenceAdvisor != null) {
              persistenceAdvisor.flushMembershipChanges();
            }
          }
        }
      }
    }
    catch(RegionDestroyedException e) {
      //ignore
    }
    catch(CancelException e) {
      //ignore
    }
    catch(VirtualMachineError e) {
      SystemFailure.initiateFailure(e);
      throw e;
    }
    catch(Throwable t) {
      SystemFailure.checkFailure();
      exception = new ReplyException(t);
    }
    finally {
      LocalRegion.setThreadInitLevelRequirement(oldLevel);
      ReplyMessage replyMsg = new ReplyMessage();
      replyMsg.setRecipient(getSender());
      replyMsg.setProcessorId(processorId);
      if(exception != null) {
        replyMsg.setException(exception);
      }
      dm.putOutgoing(replyMsg);
    }
  }

  public int getDSFID() {
    return PERSISTENT_MEMBERSHIP_FLUSH_REQUEST;
  }
  
  @Override
  public void fromData(DataInput in) throws IOException,
      ClassNotFoundException {
    super.fromData(in);
    processorId = in.readInt();
    regionPath = DataSerializer.readString(in);
  }

  @Override
  public void toData(DataOutput out) throws IOException {
    super.toData(out);
    out.writeInt(processorId);
    DataSerializer.writeString(regionPath, out);
  }
}
