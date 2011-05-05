/**
 * Copyright (C) 2010-2011, FuseSource Corp.  All rights reserved.
 *
 *     http://fusesource.com
 *
 * The software in this package is published under the terms of the
 * CDDL license a copy of which has been included with this distribution
 * in the license.txt file.
 */

package org.fusesource.fabric.apollo.amqp.broker

import org.apache.activemq.apollo.broker.{DeliverySession, DeliveryProducer, Delivery, DeliveryConsumer}
import org.fusesource.hawtbuf.AsciiBuffer
import org.apache.activemq.apollo.util.Logging
import org.fusesource.hawtdispatch._
import org.fusesource.fabric.apollo.amqp.protocol.{AmqpProtoMessage, OutgoingLink, FlowControlListener}
import org.fusesource.fabric.apollo.amqp.api.DistributionMode._
import org.fusesource.fabric.apollo.amqp.protocol.AmqpConversions._
import org.fusesource.fabric.apollo.amqp.api.{Outcome, Sender}
import org.apache.activemq.apollo.dto.{DestinationDTO, DurableSubscriptionDestinationDTO, TopicDestinationDTO}
import org.apache.activemq.apollo.filter.BooleanExpression
import org.fusesource.fabric.apollo.amqp.codec.types.{AmqpString, AmqpSymbol, AmqpFilter}
import org.apache.activemq.apollo.selector.SelectorParser
import collection.mutable.ListBuffer

/**
 * An AMQP sender that consumes message deliveries
 */
class AmqpDeliveryConsumer(h:AmqpProtocolHandler, l:Sender, var destination:Array[DestinationDTO]) extends BaseRetained with DeliveryConsumer with Logging {

  def handler = h
  def link = l
  def filters = {
    if (_filters.isEmpty) {
      Option(link.getFilter) match {
        case Some(filter_set) =>
          val iter = filter_set.iterator
          while (iter.hasNext) {
            val entry = iter.next
            val key:AmqpSymbol = entry.getKey.asInstanceOf[AmqpSymbol]
            val value:AmqpFilter = entry.getValue.asInstanceOf[AmqpFilter]
            //trace("Adding filter \"%s\" : \"%s\"", key, value)
            val expr = SelectorParser.parse(value.getPredicate.asInstanceOf[AmqpString].getValue)
            _filters.append((key.getValue, expr))
          }
        case None =>
      }
    }
    _filters
  }

  val _filters = ListBuffer[(String, BooleanExpression)]()

  val dispatch_queue = handler.dispatchQueue

  if( is_persistent ) {
    destination = destination.map { _ match {
      case x:TopicDestinationDTO=>
        val rc = new DurableSubscriptionDestinationDTO(x.name)
        rc.subscription_id = link.getName
        rc.filter = null
        rc
      case _ =>
        h.close("A persistent subscription can only be used on a topic destination")
        null.asInstanceOf[DestinationDTO]
    }
    }
  }

  def is_persistent = link.getSourceDurable

  override def connection = Some(handler.connection)
  var deliverySession:AmqpDeliverySession = null

  def matches(delivery:Delivery) : Boolean = {
    if (delivery.message.protocol eq AmqpProtocol) {
      var rc = true
      var iter = filters.iterator
      while (iter.hasNext && rc) {
        val (key, filter) = iter.next
        rc = filter.matches(delivery.message)
        //trace("Filter \"%s\" evaluated to %s for message %s", key, rc, delivery.message)
      }
      rc
    } else {
      false
    }
  }

  def connect(p:DeliveryProducer) = new AmqpDeliverySession(p)

  class AmqpDeliverySession(p:DeliveryProducer) extends DeliverySession with FlowControlListener {

    retain

    def producer = p
    def consumer = AmqpDeliveryConsumer.this
    var closed = false
    val session = handler.outbound_sessions.open(producer.dispatch_queue)

    // TODO
    link.asInstanceOf[OutgoingLink].setFlowControlListener(this)

    def canSend = refiller.run

    def remaining_capacity = session.remaining_capacity

    def close = {
      import org.fusesource.hawtdispatch.Dispatch._
      assert( getCurrentQueue == producer.dispatch_queue )
      if ( !closed ) {
        closed = true
        consumer.handler.outbound_sessions.close(session)
        //trace("Closed delivery consumer and releasing")
        release
      }
    }

    def full() : Boolean = {
        // TODO
      val out = link.asInstanceOf[OutgoingLink]
      val rc = out.canSend
      //trace("checking state of outgoing link, able to send : %s", rc)
      if (!rc) {
        try {
          out.send_updated_flow_state(out.flowstate)
        } catch {
          case _ =>
        }
      }
      !rc
    }

    def offer(delivery:Delivery) : Boolean = {
      //trace("received message offer : %s", delivery);
      val message_transfer = delivery.message.asInstanceOf[AmqpMessageTransfer]
      //trace("putting message : %s", message_transfer.message);
      val message = link.getDistributionMode match {
        case MOVE =>
          message_transfer.message
        case COPY =>
          message_transfer.message.copy
        case _ =>
          message_transfer.message
      }
      if (message.settled) {
        if (delivery.ack != null) {
          delivery.ack(true, null)
        }
      } else {
        message.onAck(^{
          message.outcome match {
            case Outcome.ACCEPTED =>
              if (delivery.ack != null) {
                delivery.ack(true, null)
              }
            case _ =>
              if (delivery.ack != null) {
                delivery.ack(false, null)
              }
          }
        })
      }

      link.put(message)
    }

    def refiller = session.refiller
    def refiller_= (value:Runnable) = { session.refiller=value }

  }
}