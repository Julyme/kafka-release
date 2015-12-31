/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kafka.tools

import java.io._
import kafka.message._
import kafka.log._
import kafka.utils._
import collection.mutable
import joptsimple.OptionParser
import kafka.serializer.Decoder
import kafka.utils.VerifiableProperties
import org.apache.kafka.common.utils.Utils

object DumpLogSegments {

  def main(args: Array[String]) {
    val parser = new OptionParser
    val printOpt = parser.accepts("print-data-log", "if set, printing the messages content when dumping data logs")
    val verifyOpt = parser.accepts("verify-index-only", "if set, just verify the index log without printing its content")
    val indexSanityOpt = parser.accepts("verify-index-sanity", "if set, just verify the index sanity without printing its content")
    val indexRecoveryOpt = parser.accepts("recover-index", "if set, just verify the index sanity without printing its content")
    val filesOpt = parser.accepts("files", "REQUIRED: The comma separated list of data and index log files to be dumped")
                           .withRequiredArg
                           .describedAs("file1, file2, ...")
                           .ofType(classOf[String])
    val maxMessageSizeOpt = parser.accepts("max-message-size", "Size of largest message.")
                                  .withRequiredArg
                                  .describedAs("size")
                                  .ofType(classOf[java.lang.Integer])
      .defaultsTo(5 * 1024 * 1024)

    val maxIndexSizeOpt = parser.accepts("max-index-size", "Max index size.")
      .withRequiredArg
      .describedAs("size")
      .ofType(classOf[java.lang.Integer])
      .defaultsTo(Defaults.MaxIndexSize)

    val indexIntervalBytesOpt = parser.accepts("index-interval-bytes", "index interval bytes")
      .withRequiredArg
      .describedAs("size")
      .ofType(classOf[java.lang.Integer])
      .defaultsTo(4096)

    val deepIterationOpt = parser.accepts("deep-iteration", "if set, uses deep instead of shallow iteration")
    val valueDecoderOpt = parser.accepts("value-decoder-class", "if set, used to deserialize the messages. This class should implement kafka.serializer.Decoder trait. Custom jar should be available in kafka/libs directory.")
                               .withOptionalArg()
                               .ofType(classOf[java.lang.String])
                               .defaultsTo("kafka.serializer.StringDecoder")
    val keyDecoderOpt = parser.accepts("key-decoder-class", "if set, used to deserialize the keys. This class should implement kafka.serializer.Decoder trait. Custom jar should be available in kafka/libs directory.")
                               .withOptionalArg()
                               .ofType(classOf[java.lang.String])
                               .defaultsTo("kafka.serializer.StringDecoder")

    if(args.length == 0)
      CommandLineUtils.printUsageAndDie(parser, "Parse a log file and dump its contents to the console, useful for debugging a seemingly corrupt log segment.")

    val options = parser.parse(args : _*)

    CommandLineUtils.checkRequiredArgs(parser, options, filesOpt)

    val print = if(options.has(printOpt)) true else false
    val verifyOnly = if(options.has(verifyOpt)) true else false
    val indexSanityOnly = if(options.has(indexSanityOpt)) true else false
    val indexRecoverOnly = if(options.has(indexRecoveryOpt)) true else false
    val files = options.valueOf(filesOpt).split(",")
    val maxMessageSize = options.valueOf(maxMessageSizeOpt).intValue()
    val indexIntervalBytes = options.valueOf(indexIntervalBytesOpt).intValue()
    val isDeepIteration = if(options.has(deepIterationOpt)) true else false
    val maxIndexSize = options.valueOf(maxIndexSizeOpt).intValue()

    val valueDecoder: Decoder[_] = CoreUtils.createObject[Decoder[_]](options.valueOf(valueDecoderOpt), new VerifiableProperties)
    val keyDecoder: Decoder[_] = CoreUtils.createObject[Decoder[_]](options.valueOf(keyDecoderOpt), new VerifiableProperties)

    val misMatchesForIndexFilesMap = new mutable.HashMap[String, List[(Long, Long)]]
    val nonConsecutivePairsForLogFilesMap = new mutable.HashMap[String, List[(Long, Long)]]

    for(arg <- files) {
      val file = new File(arg)
      if(file.getName.endsWith(Log.LogFileSuffix)) {
        println("Dumping " + file)
        dumpLog(file, print, nonConsecutivePairsForLogFilesMap, isDeepIteration, maxMessageSize , valueDecoder, keyDecoder)
      } else if(file.getName.endsWith(Log.IndexFileSuffix)) {
        if (indexRecoverOnly) {
          println("recovering " + file)
          recoverIndex(file, maxMessageSize, indexIntervalBytes, maxIndexSize)
        } else {
          println("Dumping " + file)
          dumpIndex(file, verifyOnly, indexSanityOnly, misMatchesForIndexFilesMap, maxMessageSize)
        }
      }
    }
    misMatchesForIndexFilesMap.foreach {
      case (fileName, listOfMismatches) => {
        System.err.println("Mismatches in :" + fileName)
        listOfMismatches.foreach(m => {
          System.err.println("  Index offset: %d, log offset: %d".format(m._1, m._2))
        })
      }
    }
    nonConsecutivePairsForLogFilesMap.foreach {
      case (fileName, listOfNonConsecutivePairs) => {
        System.err.println("Non-secutive offsets in :" + fileName)
        listOfNonConsecutivePairs.foreach(m => {
          System.err.println("  %d is followed by %d".format(m._1, m._2))
        })
      }
    }
  }

  /* print out the contents of the index */
  private def dumpIndex(file: File,
                        verifyOnly: Boolean,
                        indexSanityOnly: Boolean,
                        misMatchesForIndexFilesMap: mutable.HashMap[String, List[(Long, Long)]],
                        maxMessageSize: Int) {
    val startOffset = file.getName().split("\\.")(0).toLong
    val index = new OffsetIndex(file = file, baseOffset = startOffset)
    if (indexSanityOnly) {
      index.sanityCheck
      return
    }
    val logFile = new File(file.getAbsoluteFile.getParent, file.getName.split("\\.")(0) + Log.LogFileSuffix)
    val messageSet = new FileMessageSet(logFile, false)
    for(i <- 0 until index.entries) {
      val entry = index.entry(i)
      val partialFileMessageSet: FileMessageSet = messageSet.read(entry.position, maxMessageSize)
      val messageAndOffset = getIterator(partialFileMessageSet.head, isDeepIteration = true).next()
      if(messageAndOffset.offset != entry.offset + index.baseOffset) {
        var misMatchesSeq = misMatchesForIndexFilesMap.getOrElse(file.getAbsolutePath, List[(Long, Long)]())
        misMatchesSeq ::=(entry.offset + index.baseOffset, messageAndOffset.offset)
        misMatchesForIndexFilesMap.put(file.getAbsolutePath, misMatchesSeq)
      }
      // since it is a sparse file, in the event of a crash there may be many zero entries, stop if we see one
      if(entry.offset == 0 && i > 0)
        return
      if (!verifyOnly)
        println("offset: %d position: %d".format(entry.offset + index.baseOffset, entry.position))
    }
  }

  /* print out the contents of the log */
  private def dumpLog(file: File,
                      printContents: Boolean,
                      nonConsecutivePairsForLogFilesMap: mutable.HashMap[String, List[(Long, Long)]],
                      isDeepIteration: Boolean,
                      maxMessageSize: Int,
                      valueDecoder: Decoder[_],
                      keyDecoder: Decoder[_]) {
    val startOffset = file.getName().split("\\.")(0).toLong
    println("Starting offset: " + startOffset)
    val messageSet = new FileMessageSet(file, false)
    var validBytes = 0L
    var lastOffset = -1l
    val shallowIterator = messageSet.iterator(maxMessageSize)
    for(shallowMessageAndOffset <- shallowIterator) { // this only does shallow iteration
      val itr = getIterator(shallowMessageAndOffset, isDeepIteration)
      for (messageAndOffset <- itr) {
        val msg = messageAndOffset.message

        if(lastOffset == -1)
          lastOffset = messageAndOffset.offset
        // If we are iterating uncompressed messages, offsets must be consecutive
        else if (msg.compressionCodec == NoCompressionCodec && messageAndOffset.offset != lastOffset +1) {
          var nonConsecutivePairsSeq = nonConsecutivePairsForLogFilesMap.getOrElse(file.getAbsolutePath, List[(Long, Long)]())
          nonConsecutivePairsSeq ::=(lastOffset, messageAndOffset.offset)
          nonConsecutivePairsForLogFilesMap.put(file.getAbsolutePath, nonConsecutivePairsSeq)
        }
        lastOffset = messageAndOffset.offset

        print("offset: " + messageAndOffset.offset + " position: " + validBytes + " isvalid: " + msg.isValid +
              " payloadsize: " + msg.payloadSize + " magic: " + msg.magic +
              " compresscodec: " + msg.compressionCodec + " crc: " + msg.checksum)
        if(msg.hasKey)
          print(" keysize: " + msg.keySize)
        if(printContents) {
          if(msg.hasKey)
            print(" key: " + keyDecoder.fromBytes(Utils.readBytes(messageAndOffset.message.key)))
          val payload = if(messageAndOffset.message.isNull) null else valueDecoder.fromBytes(Utils.readBytes(messageAndOffset.message.payload))
          print(" payload: " + payload)
        }
        println()
      }
      validBytes += MessageSet.entrySize(shallowMessageAndOffset.message)
    }
    val trailingBytes = messageSet.sizeInBytes - validBytes
    if(trailingBytes > 0)
      println("Found %d invalid bytes at the end of %s".format(trailingBytes, file.getName))
  }

  private def getIterator(messageAndOffset: MessageAndOffset, isDeepIteration: Boolean) = {
    if (isDeepIteration) {
      val message = messageAndOffset.message
      message.compressionCodec match {
        case NoCompressionCodec =>
          getSingleMessageIterator(messageAndOffset)
        case _ =>
          ByteBufferMessageSet.deepIterator(message)
      }
    } else
      getSingleMessageIterator(messageAndOffset)
  }

  private def getSingleMessageIterator(messageAndOffset: MessageAndOffset) = {
    new IteratorTemplate[MessageAndOffset] {
      var messageIterated = false

      override def makeNext(): MessageAndOffset = {
        if (!messageIterated) {
          messageIterated = true
          messageAndOffset
        } else
          allDone()
      }
    }
  }

  /**
    * Run recovery on the given segment. This will rebuild the index from the log file and lop off any invalid bytes from the end of the log and index.
    *
    * @param maxMessageSize A bound the memory allocation in the case of a corrupt message size--we will assume any message larger than this
    * is corrupt.
    *
    * @return The number of bytes truncated from the log
    */
  @nonthreadsafe
  def recoverIndex(file: File, maxMessageSize: Int, indexIntervalBytes: Int, maxIndexSize: Int): Int = {
    val startOffset1 = file.getName().split("\\.")(0).toLong
    val index = new OffsetIndex(file = file, baseOffset = startOffset1, maxIndexSize = maxIndexSize)
    val logFile = new File(file.getAbsoluteFile.getParent, file.getName.split("\\.")(0) + Log.LogFileSuffix)
    println("log file " + logFile)
    index.truncate()
    index.resize(maxIndexSize);
    println("Max allowed entries are "  + index.maxEntries + " and max allowed size is " + index.maxIndexSize)
    val log = new FileMessageSet(logFile, false)
    var validBytes = 0
    var lastIndexEntry = 0
    val iter = log.iterator(maxMessageSize)
    try {
      while(iter.hasNext) {
        val entry = iter.next
        entry.message.ensureValid()
        if(validBytes - lastIndexEntry > indexIntervalBytes) {
          // we need to decompress the message, if required, to get the offset of the first uncompressed message
          val startOffset =
            entry.message.compressionCodec match {
              case NoCompressionCodec =>
                entry.offset
              case _ =>
                ByteBufferMessageSet.deepIterator(entry.message).next().offset
            }
          index.append(startOffset, validBytes)
          lastIndexEntry = validBytes
        }
        validBytes += MessageSet.entrySize(entry.message)
      }
    } catch {
      case e: InvalidMessageException =>
        println("Found invalid messages in log segment %s at byte offset %d: %s.".format(log.file.getAbsolutePath, validBytes, e.getMessage))
    }
    println("total added indexes " + index.entries())
    val truncated = log.sizeInBytes - validBytes
    println(s"log has bytes =  ${log.sizeInBytes()} and validBytes after index rebuilding is only $validBytes, so $truncated bytes should be truncated." +
      "This is not currently done as we don't want to touch the actual data segment in anyway.")
      //s" Are you sure you want to truncate the log it self , (THIS IS NOT INDEX BUT THE ACTUAL DATA FILE, VERY DANGEROUS...)")
    //log.truncateTo(validBytes)
    index.trimToValidSize()
    truncated
  }
}
