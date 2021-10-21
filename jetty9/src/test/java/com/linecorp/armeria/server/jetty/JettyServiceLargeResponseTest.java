/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server.jetty;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Arrays;
import java.util.Random;

import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class JettyServiceLargeResponseTest {

    private static final long SEED = 4897234712L;
    private static final Random serverRandom = new Random(SEED);
    private static final Random clientRandom = new Random(SEED);

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.http(0);
            sb.https(0);
            sb.tlsSelfSigned();

            sb.service(
                    "/stream",
                    newJettyService((req, res) -> {
                        final ServletOutputStream out = res.getOutputStream();
                        res.setStatus(200);
                        try {
                            final byte[] content = new byte[nextLength(serverRandom)];
                            serverRandom.nextBytes(content);
                            out.write(content);
                            out.close();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }));
        }
    };

    @Test
    void test() throws Exception {
        final WebClient client = WebClient.builder().maxResponseLength(0).build();
        for (int i = 0;; i++) {
            if (i % 100 == 0) {
                System.err.println(i);
            }

            final AggregatedHttpResponse res =
                    client.get(server.httpUri() + "/stream")
                          .aggregate()
                          .join();

            assertThat(res.status()).isSameAs(HttpStatus.OK);
            final byte[] content = res.content().array();
            final int expectedLength = nextLength(clientRandom);
            assertThat(content).hasSize(expectedLength);
            final byte[] expectedContent = new byte[expectedLength];
            clientRandom.nextBytes(expectedContent);
            assertThat(Arrays.equals(content, expectedContent)).isTrue();
        }
    }

    private static int nextLength(Random random) {
        return random.nextInt(10485760);
    }

    private static JettyService newJettyService(SimpleHandlerFunction func) {
        return JettyService.builder()
                           .handler(new AbstractHandler() {
                               @Override
                               public void handle(String target, Request baseRequest,
                                                  HttpServletRequest request,
                                                  HttpServletResponse response) throws ServletException {
                                   try {
                                       func.handle(baseRequest, (Response) response);
                                   } catch (Throwable t) {
                                       throw new ServletException(t);
                                   }
                               }
                           })
                           .build();
    }

    @FunctionalInterface
    private interface SimpleHandlerFunction {
        void handle(Request req, Response res) throws Throwable;
    }
}
