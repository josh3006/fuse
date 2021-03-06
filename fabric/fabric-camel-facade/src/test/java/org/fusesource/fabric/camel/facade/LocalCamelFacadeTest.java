/*
 * Copyright 2010 Red Hat, Inc.
 *
 *  Red Hat licenses this file to you under the Apache License, version
 *  2.0 (the "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 *  implied.  See the License for the specific language governing
 *  permissions and limitations under the License.
 */

package org.fusesource.fabric.camel.facade;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.fusesource.fabric.camel.facade.mbean.CamelBrowsableEndpointMBean;
import org.fusesource.fabric.camel.facade.mbean.CamelComponentMBean;
import org.fusesource.fabric.camel.facade.mbean.CamelConsumerMBean;
import org.fusesource.fabric.camel.facade.mbean.CamelContextMBean;
import org.fusesource.fabric.camel.facade.mbean.CamelEndpointMBean;
import org.fusesource.fabric.camel.facade.mbean.CamelFabricTracerMBean;
import org.fusesource.fabric.camel.facade.mbean.CamelProcessorMBean;
import org.fusesource.fabric.camel.facade.mbean.CamelRouteMBean;
import org.fusesource.fabric.camel.facade.mbean.CamelSendProcessorMBean;
import org.fusesource.fabric.camel.facade.mbean.CamelThreadPoolMBean;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.fabric.FabricTracerEventMessage;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.spi.BrowsableEndpoint;
import org.apache.camel.test.junit4.CamelTestSupport;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.w3c.dom.Document;

/**
 *
 */
public class LocalCamelFacadeTest extends CamelTestSupport {

    private LocalCamelFacade local;
    private String name = "myCamel";

    @Before
    public void setUp() throws Exception {
        super.setUp();
        local = new LocalCamelFacade(context());
    }

    @Override
    protected boolean useJmx() {
        return true;
    }

    @Override
    protected CamelContext createCamelContext() throws Exception {
        DefaultCamelContext answer = new DefaultCamelContext();
        answer.setName(name);
        // simulate we are bundle number 19
        answer.getManagementNameStrategy().setNamePattern("19-#name#");
        return answer;
    }

    @Test
    public void testGetId() throws Exception {
        String name = local.getCamelContext(context.getManagementName()).getCamelId();
        String managementName = local.getCamelContext(context.getManagementName()).getManagementName();
        assertEquals(context.getName(), name);
        assertEquals(context.getManagementName(), managementName);
        assertEquals("myCamel", name);
        assertEquals("19-myCamel", managementName);
    }

    @Test
    public void testGetCamelContext() throws Exception {
        CamelContextMBean localContext = local.getCamelContext(context.getManagementName());
        assertNotNull(localContext);
        assertEquals("myCamel", localContext.getCamelId());
        assertEquals(context.getVersion(), localContext.getCamelVersion());
        assertNotNull(localContext.getUptime());
    }

    @Test
    public void testGetRoutes() throws Exception {
        int size = context.getRoutes().size();
        assertEquals(size, local.getRoutes(context.getManagementName()).size());

        CamelRouteMBean route = local.getRoutes(context.getManagementName()).get(0);
        assertNotNull(route);
        assertEquals("in-route", route.getRouteId());
        assertEquals("seda://in", route.getEndpointUri());
    }

    @Test
    public void testGetComponents() throws Exception {
        int size = context.getComponentNames().size();
        assertEquals(size, local.getComponents(context.getManagementName()).size());

        List<CamelComponentMBean> components = local.getComponents(context.getManagementName());
        for (CamelComponentMBean component : components) {
            assertTrue(component.getComponentName(), component.getComponentName().matches("(seda|log)"));
        }
    }

    @Test
    public void testGetConsumers() throws Exception {
        List<CamelConsumerMBean> consumers = local.getConsumers(context.getManagementName());
        assertEquals(1, consumers.size());

        assertEquals("seda://in", consumers.get(0).getEndpointUri());
        assertEquals(0, consumers.get(0).getInflightExchanges().intValue());
        assertEquals("myCamel", consumers.get(0).getCamelId());
        assertEquals("in-route", consumers.get(0).getRouteId());
        assertEquals("Started", consumers.get(0).getState());
    }

    @Test
    public void testGetProcessors() throws Exception {
        List<CamelProcessorMBean> processors = local.getProcessors(context.getManagementName());
        assertEquals(2, processors.size());

        for (CamelProcessorMBean processor : processors) {
            assertTrue(processor.getProcessorId(), processor.getProcessorId().matches("(toLog|toOut)"));
            assertEquals("myCamel", processor.getCamelId());
            assertEquals("in-route", processor.getRouteId());
            assertEquals("Started", processor.getState());

            CamelSendProcessorMBean send = assertIsInstanceOf(CamelSendProcessorMBean.class, processor);
            assertTrue(send.getDestination(), send.getDestination().matches("(log://in|seda://out)"));
        }
    }

    @Test
    public void testGetEndpoints() throws Exception {
        int size = context.getEndpoints().size();
        assertEquals(size, local.getEndpoints(context.getManagementName()).size());
        assertEquals(3, size);

        // there should be 3 endpoints
        List<CamelEndpointMBean> endpoints = local.getEndpoints(context.getManagementName());

        // endpoints can be in "random" order
        CamelEndpointMBean endpoint1 = endpoints.get(0);
        CamelEndpointMBean endpoint2 = endpoints.get(1);
        CamelEndpointMBean endpoint3 = endpoints.get(2);
        assertNotNull(endpoint1);
        assertNotNull(endpoint2);
        assertNotNull(endpoint3);

        List<String> uris = new ArrayList<String>();
        uris.add(endpoint1.getEndpointUri());
        uris.add(endpoint2.getEndpointUri());
        uris.add(endpoint3.getEndpointUri());
        Collections.sort(uris);

        assertEquals("log://in", uris.get(0));
        assertEquals("seda://in", uris.get(1));
        assertEquals("seda://out", uris.get(2));
    }

    @Test
    @Ignore
    public void testBrowsableEndpoints() throws Exception {
        template.sendBody("seda:out", "Hello World");

        List<CamelEndpointMBean> endpoints = local.getEndpoints(context.getManagementName());

        // get seda:out endpoint
        CamelBrowsableEndpointMBean browsable = null;
        for (CamelEndpointMBean endpoint : endpoints) {
            if (endpoint.getEndpointUri().endsWith("seda://out")) {
                browsable = (CamelBrowsableEndpointMBean) endpoint;
                break;
            }
        }

        assertNotNull("Should find browsable", browsable);

        Exchange exchange = context.getEndpoint("seda:out", BrowsableEndpoint.class).getExchanges().get(0);

        assertEquals(1, browsable.queueSize());
        assertEquals("Hello World", browsable.browseMessageBody(0));
        assertEquals("<message exchangeId=\"" + exchange.getExchangeId() + "\">\n"
                + "<headers>\n"
                + "<header key=\"breadcrumbId\" type=\"java.lang.String\">" + exchange.getIn().getHeader(Exchange.BREADCRUMB_ID) + "</header>\n"
                + "</headers>\n"
                + "<body type=\"java.lang.String\">Hello World</body>\n"
                + "</message>", browsable.browseMessageAsXml(0, true));
    }

    @Test
    public void testFabricTracer() throws Exception {
        CamelFabricTracerMBean tracer = local.getFabricTracer(context.getManagementName());
        assertNotNull(tracer);
        assertEquals("Should be disabled by default", false, tracer.isEnabled());

        // enable it
        tracer.setEnabled(true);

        template.sendBody("seda:in", "Hello World");
        template.sendBody("seda:in", "Bye World");

        Thread.sleep(2000);

        List<FabricTracerEventMessage> node1 = tracer.dumpTracedMessages("toLog");
        List<FabricTracerEventMessage> node2 = tracer.dumpTracedMessages("toOut");
        assertNotNull(node1);
        assertNotNull(node2);
        assertEquals(2, node1.size());
        assertEquals(2, node2.size());

        // the exchange id should correlate
        assertEquals(node1.get(0).getExchangeId(), node2.get(0).getExchangeId());
        assertEquals(node1.get(1).getExchangeId(), node2.get(1).getExchangeId());

        // check data at node 2
        FabricTracerEventMessage event1 = node2.get(0);
        assertEquals("toOut", event1.getToNode());
        assertNotNull(event1.getTimestamp());
        assertNotNull(event1.getExchangeId());
        String s = "<body type=\"java.lang.String\">Hello World</body>\n</message>";
        assertTrue(event1.getMessageAsXml().endsWith(s));

        FabricTracerEventMessage event2 = node2.get(1);
        assertEquals("toOut", event2.getToNode());
        assertNotNull(event2.getTimestamp());
        assertNotNull(event2.getExchangeId());
        String s2 = "<body type=\"java.lang.String\">Bye World</body>\n</message>";
        assertTrue(event2.getMessageAsXml().endsWith(s2));

        // should not be same exchange id as its 2 different exchanges
        assertNotSame(event1.getExchangeId(), event2.getExchangeId());
    }

    @Test
    public void testFabricTracerAsXml() throws Exception {
        CamelFabricTracerMBean tracer = local.getFabricTracer(context.getManagementName());
        assertNotNull(tracer);
        assertEquals("Should be disabled by default", false, tracer.isEnabled());

        // enable it
        tracer.setEnabled(true);

        template.sendBody("seda:in", "Hello World");
        template.sendBody("seda:in", "Bye World");

        Thread.sleep(2000);

        String xml = tracer.dumpAllTracedMessagesAsXml();
        log.info(xml);

        assertNotNull(xml);

        // just a quick and dirty test
        assertTrue(xml.startsWith("<fabricTracerEventMessages>"));
        assertTrue(xml.contains("Hello World"));
        assertTrue(xml.contains("Bye World"));
        assertTrue(xml.endsWith("</fabricTracerEventMessages>"));
    }

    @Test
    public void testThreadPools() throws Exception {
        List<CamelThreadPoolMBean> pools = local.getThreadPools(context.getManagementName());
        assertNotNull(pools);
        assertEquals(2, pools.size());
    }

    @Test
    public void testDumpRoutesStatsAsXml() throws Exception {
        template.sendBody("seda:in", "Hello World");

        String xml = local.dumpRoutesStatsAsXml(context.getManagementName());
        assertNotNull(xml);

        // should be valid XML
        Document doc = context().getTypeConverter().convertTo(Document.class, xml);
        assertNotNull(doc);

        int processors = doc.getDocumentElement().getElementsByTagName("processorStat").getLength();
        assertEquals(2, processors);
    }

    @Override
    protected RouteBuilder createRouteBuilder() throws Exception {
        return new RouteBuilder() {
            @Override
            public void configure() throws Exception {
                from("seda:in").routeId("in-route")
                        .to("log:in").id("toLog")
                        .to("seda:out").id("toOut");
            }
        };
    }

}
