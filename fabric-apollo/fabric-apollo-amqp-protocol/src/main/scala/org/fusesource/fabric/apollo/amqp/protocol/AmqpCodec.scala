/**
 * Copyright (C) 2010-2011, FuseSource Corp.  All rights reserved.
 *
 *     http://fusesource.com
 *
 * The software in this package is published under the terms of the
 * CDDL license a copy of which has been included with this distribution
 * in the license.txt file.
 */

package org.fusesource.fabric.apollo.amqp.protocol

import org.apache.activemq.apollo.transport._
import java.nio.channels._
import java.nio.ByteBuffer
import org.fusesource.hawtbuf.{DataByteArrayOutputStream, Buffer}
import org.fusesource.fabric.apollo.amqp.codec.marshaller.AmqpProtocolHeaderCodec
import java.io.{DataOutputStream, DataInputStream, EOFException}
import org.fusesource.fabric.apollo.amqp.protocol.AmqpConstants._
import org.apache.activemq.apollo.broker.Sizer
import org.apache.activemq.apollo.util.Logging
import org.fusesource.fabric.apollo.amqp.codec.types.AmqpType
import java.net.SocketException
import org.fusesource.fabric.apollo.amqp.codec._


/*
*
*/
class AmqpProtocolCodecFactory extends ProtocolCodecFactory.Provider {

  def protocol = PROTOCOL

  def createProtocolCodec = new AmqpCodec

  def isIdentifiable = true

  def maxIdentificaionLength = MAGIC.length

  def matchesIdentification(buffer: Buffer) : Boolean = {
    buffer.startsWith(MAGIC)
  }
}

object AmqpCodec extends Sizer[AnyRef] {

  def size(value: AnyRef) = {
    value match {
      case x:AmqpProtocolHeader=> AmqpProtocolHeaderCodec.INSTANCE.getFixedSize
      case x:AmqpFrame=> x.getFrameSize.toInt

    }
  }
}

class AmqpCodec extends ProtocolCodec with Logging {

  implicit def toBuffer(value:Array[Byte]):Buffer = new Buffer(value)

  def protocol = PROTOCOL

  var write_buffer_size = 1024*64;
  var write_counter = 0L
  var write_channel:WritableByteChannel = null

  var next_write_buffer = new DataByteArrayOutputStream(write_buffer_size)
  var write_buffer = ByteBuffer.allocate(0)

  def full = next_write_buffer.size() >= (write_buffer_size >> 1)
  def is_empty = write_buffer.remaining() == 0

  def setWritableByteChannel(channel: WritableByteChannel) = {
    this.write_channel = channel
    if( this.write_channel.isInstanceOf[SocketChannel] ) {
      try {
        this.write_channel.asInstanceOf[SocketChannel].socket().setSendBufferSize(write_buffer_size);
      } catch {
        case e:SocketException => warn("Unable to set write buffer size to " + write_buffer_size + " using " + this.write_channel.asInstanceOf[SocketChannel].socket().getSendBufferSize)
      }
      write_buffer_size = this.write_channel.asInstanceOf[SocketChannel].socket().getSendBufferSize
    }
  }

  def getWriteCounter = write_counter

  def write(command: Any):ProtocolCodec.BufferState =  {
    if ( full) {
      ProtocolCodec.BufferState.FULL
    } else {
      val was_empty = is_empty
      //debug("Sending %s", command);
      command match {
        case frame:AmqpProtocolHeader=>
          AmqpProtocolHeaderCodec.INSTANCE.encode(frame, new DataOutputStream(next_write_buffer))
        case frame:AmqpFrame=>
          frame.write(new DataOutputStream(next_write_buffer))
      }
      if( was_empty ) {
        ProtocolCodec.BufferState.WAS_EMPTY
      } else {
        ProtocolCodec.BufferState.NOT_EMPTY
      }
    }
  }

  def flush():ProtocolCodec.BufferState = {
    // if we have a pending write that is being sent over the socket...
    if ( write_buffer.remaining() != 0 ) {
      //trace("Remaining data in write buffer : %s bytes", write_buffer.remaining())
      val bytes_out = write_channel.write(write_buffer)
      //trace("Wrote %s bytes", bytes_out);
      write_counter += bytes_out
    }


    // if it is now empty try to refill...
    if ( is_empty && next_write_buffer.size()!=0 ) {
        // size of next buffer is based on how much was used in the previous buffer.
        val prev_size = (write_buffer.position()+512).max(512).min(write_buffer_size)
        write_buffer = next_write_buffer.toBuffer().toByteBuffer()
        next_write_buffer = new DataByteArrayOutputStream(prev_size)
        //trace("Current write buffer size is %s bytes, next write buffer size is %s bytes", write_buffer.remaining, prev_size)
    }

    if ( is_empty ) {
      ProtocolCodec.BufferState.EMPTY
    } else {
      ProtocolCodec.BufferState.NOT_EMPTY
    }
  }

  var read_counter = 0L
  var read_buffer_size = 1024*64
  var read_channel:ReadableByteChannel = null

  var next_action:()=>AnyRef = read_protocol_header
  var read_buffer:ByteBuffer = ByteBuffer.allocate(AmqpProtocolHeaderCodec.INSTANCE.getFixedSize)
  var read_waiting_on = AmqpProtocolHeaderCodec.INSTANCE.getFixedSize

  def setReadableByteChannel(channel: ReadableByteChannel) = {
    this.read_channel = channel
    if( this.read_channel.isInstanceOf[SocketChannel] ) {
      try {
        this.read_channel.asInstanceOf[SocketChannel].socket().setReceiveBufferSize(read_buffer_size);
      } catch {
        case e:SocketException => warn("Unable to set receive buffer size to " + read_buffer_size + " using " + this.read_channel.asInstanceOf[SocketChannel].socket().getReceiveBufferSize)
      }
      read_buffer_size = this.read_channel.asInstanceOf[SocketChannel].socket().getReceiveBufferSize
    }
  }

  def unread(buffer: Buffer) = {
    assert(read_counter == 0)
    read_buffer = ByteBuffer.allocate(buffer.length.max(read_waiting_on))
    read_buffer.put(buffer.data, buffer.offset, buffer.length)
    read_counter += buffer.length
    read_waiting_on -= buffer.length
    if ( read_waiting_on <= 0 ) {
      read_buffer.flip
    }
  }

  def getReadCounter = read_counter

  override def read():Object = {

    var command:Object = null
    while( command==null ) {
      // do we need to read in more data???
      if ( read_waiting_on > 0 ) {
        assert(read_buffer.remaining >= read_waiting_on, "read_buffer too small")

        // Try to fill the buffer with data from the socket..
        var count = read_channel.read(read_buffer)
        //trace("Read in %s bytes", count)
        if (count == -1) {
            throw new EOFException("Peer disconnected")
        } else if (count == 0) {
            return null
        }
        read_counter += count
        read_waiting_on -= count

        if ( read_waiting_on <= 0 ) {
          read_buffer.flip
        }

      } else {
        command = next_action()
        if (read_waiting_on > read_buffer.remaining) {
          val next_buffer = try {
             ByteBuffer.allocate((read_buffer.limit - read_buffer.position) + read_waiting_on)
          } catch {
            case o: OutOfMemoryError =>
              error("Caught OOM allocating read buffer %s", o)
              throw o
            case t: Throwable =>
              error("Caught exception allocating read buffer %s", t)
              throw t
          }
          // Move any unread bytes into the next buffer.. (don't think we ever have any)
          next_buffer.put(read_buffer)
          read_buffer = next_buffer
        }
      }
    }
    //debug("Received %s", command)
    return command
  }

  def read_protocol_header:()=>AnyRef = ()=> {
    val protocol_header = AmqpProtocolHeaderCodec.INSTANCE.decode(new DataInputStream(read_buffer.array.in))
    val new_pos = read_buffer.position + AmqpProtocolHeaderCodec.INSTANCE.getFixedSize
    read_buffer.position(new_pos)
    //trace("Read protocol header, read_buffer position : %s", read_buffer.position)

    read_waiting_on += 8
    next_action = read_frame_header
    protocol_header
  }

  def read_frame_header:()=>AnyRef = ()=> {
    //trace("Waiting to read in 8 byte frame header");
    read_buffer.mark
    val buf = new Array[Byte](8)
    read_buffer.get(buf)
    //trace("Read in %s", buf.map{(x) => String.format("0x%02X", java.lang.Byte.valueOf(x))}.mkString(" "))
    val size = BitUtils.getUInt(buf, 0).asInstanceOf[Int]
    //trace("Frame size : %s", size)
    read_buffer.reset
    read_waiting_on += (size - 8)
    next_action = read_frame(size)
    null
  }

  def read_frame(size:Int): ()=>AnyRef = ()=> {
    //trace("Next frame to read in is %s bytes, read_buffer.position=%s, read_buffer.array length=%s", size, read_buffer.position, read_buffer.array.length)
    val buf = new Buffer(read_buffer.array, read_buffer.position, size)
    //trace("Read in : %s", buf)
    val rc = new AmqpFrame(new DataInputStream(buf.in))
    read_buffer.position(read_buffer.position+size)

    //trace("Read frame, read buffer position : %s", read_buffer.position)

    read_waiting_on += 8
    next_action = read_frame_header
    rc
  }

}
