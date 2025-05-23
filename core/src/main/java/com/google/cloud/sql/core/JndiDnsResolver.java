/*
 * Copyright 2024 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.sql.core;

import java.util.Collection;
import java.util.Collections;
import java.util.Hashtable;
import java.util.stream.Collectors;
import javax.naming.Context;
import javax.naming.NameNotFoundException;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.InitialDirContext;

/** Implements DnsResolver using the Java JNDI built-in DNS directory. */
class JndiDnsResolver implements DnsResolver {
  private final String jndiPrefix;

  /** Creates a resolver using the system DNS settings. */
  JndiDnsResolver() {
    this.jndiPrefix = "dns:";
  }

  /**
   * Creates a DNS resolver that uses a specific DNS server.
   *
   * @param dnsServer the DNS server hostname
   * @param port the DNS server port (DNS servers usually use port 53)
   */
  JndiDnsResolver(String dnsServer, int port) {
    this.jndiPrefix = "dns://" + dnsServer + ":" + port + "/";
  }

  /**
   * Returns DNS records for a domain name, sorted alphabetically.
   *
   * @param domainName the domain name to lookup
   * @return the list of record
   * @throws javax.naming.NameNotFoundException when the domain name did not resolve.
   */
  @Override
  @SuppressWarnings("JdkObsolete")
  public Collection<String> resolveTxt(String domainName)
      throws javax.naming.NameNotFoundException {
    try {
      // Notice: This is old Java 1.2 style code. It uses the ancient JNDI DNS Provider api.
      // See https://docs.oracle.com/javase/7/docs/technotes/guides/jndi/jndi-dns.html

      // Explicitly reference the JNDI DNS classes. This is required for GraalVM.
      Hashtable<String, String> contextProps = new Hashtable<>();
      contextProps.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.dns.DnsContextFactory");
      contextProps.put(Context.OBJECT_FACTORIES, "com.sun.jndi.url.dns.dnsURLContextFactory");
      Attribute attr =
          new InitialDirContext(contextProps)
              .getAttributes(jndiPrefix + domainName, new String[] {"TXT"})
              .get("TXT");
      // attr.getAll() returns a Vector containing strings, one for each record returned by dns.
      return Collections.list(attr.getAll()).stream()
          .map((Object v) -> (String) v)
          .sorted() // sort multiple records alphabetically
          .collect(Collectors.toList());
    } catch (NameNotFoundException e) {
      throw e;
    } catch (NamingException e) {
      throw new RuntimeException("Unable to look up domain name " + domainName, e);
    }
  }
}
