/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.sdk.extensions.protobuf;

import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.apache.beam.sdk.coders.CannotProvideCoderException;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.coders.CoderProvider;
import org.apache.beam.sdk.coders.CoderRegistry;
import org.apache.beam.sdk.coders.DefaultCoder;
import org.apache.beam.sdk.values.TypeDescriptor;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.collect.ImmutableSet;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.collect.Sets;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A {@link Coder} using Google Protocol Buffers binary format. {@link DynamicProtoCoder} supports
 * both Protocol Buffers syntax versions 2 and 3.
 *
 * <p>To learn more about Protocol Buffers, visit: <a
 * href="https://developers.google.com/protocol-buffers">https://developers.google.com/protocol-buffers</a>
 *
 * <p>{@link DynamicProtoCoder} is not registered in the global {@link CoderRegistry} as the
 * descriptor is required to create the coder.
 */
public class DynamicProtoCoder extends ProtoCoder<DynamicMessage> {

  public static final long serialVersionUID = 1L;

  /**
   * Returns a {@link DynamicProtoCoder} for the Protocol Buffers {@link DynamicMessage} for the
   * given {@link Descriptors.Descriptor}.
   */
  public static DynamicProtoCoder of(Descriptors.Descriptor protoMessageDescriptor) {
    return new DynamicProtoCoder(
        ProtoDomain.buildFrom(protoMessageDescriptor),
        protoMessageDescriptor.getFullName(),
        ImmutableSet.of());
  }

  /**
   * Returns a {@link DynamicProtoCoder} for the Protocol Buffers {@link DynamicMessage} for the
   * given {@link Descriptors.Descriptor}. The message descriptor should be part of the provided
   * {@link ProtoDomain}, this will ensure object equality within messages from the same domain.
   */
  public static DynamicProtoCoder of(
      ProtoDomain domain, Descriptors.Descriptor protoMessageDescriptor) {
    return new DynamicProtoCoder(domain, protoMessageDescriptor.getFullName(), ImmutableSet.of());
  }

  /**
   * Returns a {@link DynamicProtoCoder} for the Protocol Buffers {@link DynamicMessage} for the
   * given message name in a {@link ProtoDomain}. The message descriptor should be part of the
   * provided * {@link ProtoDomain}, this will ensure object equality within messages from the same
   * domain.
   */
  public static DynamicProtoCoder of(ProtoDomain domain, String messageName) {
    return new DynamicProtoCoder(domain, messageName, ImmutableSet.of());
  }

  /**
   * Returns a {@link DynamicProtoCoder} like this one, but with the extensions from the given
   * classes registered.
   *
   * <p>Each of the extension host classes must be an class automatically generated by the Protocol
   * Buffers compiler, {@code protoc}, that contains messages.
   *
   * <p>Does not modify this object.
   */
  @Override
  public DynamicProtoCoder withExtensionsFrom(Iterable<Class<?>> moreExtensionHosts) {
    validateExtensions(moreExtensionHosts);
    return new DynamicProtoCoder(
        this.domain,
        this.messageName,
        new ImmutableSet.Builder<Class<?>>()
            .addAll(extensionHostClasses)
            .addAll(moreExtensionHosts)
            .build());
  }

  @Override
  public boolean equals(@Nullable Object other) {
    if (this == other) {
      return true;
    }
    if (other == null || getClass() != other.getClass()) {
      return false;
    }
    DynamicProtoCoder otherCoder = (DynamicProtoCoder) other;
    return protoMessageClass.equals(otherCoder.protoMessageClass)
        && Sets.newHashSet(extensionHostClasses)
            .equals(Sets.newHashSet(otherCoder.extensionHostClasses))
        && domain.equals(otherCoder.domain)
        && messageName.equals(otherCoder.messageName);
  }

  @Override
  public int hashCode() {
    return Objects.hash(protoMessageClass, extensionHostClasses, domain, messageName);
  }

  ////////////////////////////////////////////////////////////////////////////////////
  // Private implementation details below.

  // Constants used to serialize and deserialize
  private static final String PROTO_MESSAGE_CLASS = "dynamic_proto_message_class";
  private static final String PROTO_EXTENSION_HOSTS = "dynamic_proto_extension_hosts";

  // Descriptor used by DynamicMessage.
  private transient ProtoDomain domain;
  private transient String messageName;

  private DynamicProtoCoder(
      ProtoDomain domain, String messageName, Set<Class<?>> extensionHostClasses) {
    super(DynamicMessage.class, extensionHostClasses);
    this.domain = domain;
    this.messageName = messageName;
  }

  private void writeObject(ObjectOutputStream oos) throws IOException {
    oos.defaultWriteObject();
    oos.writeObject(domain);
    oos.writeObject(messageName);
  }

  private void readObject(ObjectInputStream ois) throws ClassNotFoundException, IOException {
    ois.defaultReadObject();
    this.domain = (ProtoDomain) ois.readObject();
    this.messageName = (String) ois.readObject();
  }

  /** Get the memoized {@link Parser}, possibly initializing it lazily. */
  @Override
  protected Parser<DynamicMessage> getParser() {
    if (memoizedParser == null) {
      DynamicMessage protoMessageInstance =
          DynamicMessage.newBuilder(domain.getDescriptor(messageName)).build();
      memoizedParser = protoMessageInstance.getParserForType();
    }
    return memoizedParser;
  }

  /**
   * Returns a {@link CoderProvider} which uses the {@link DynamicProtoCoder} for {@link Message
   * proto messages}.
   *
   * <p>This method is invoked reflectively from {@link DefaultCoder}.
   */
  public static CoderProvider getCoderProvider() {
    return new ProtoCoderProvider();
  }

  static final TypeDescriptor<Message> MESSAGE_TYPE = new TypeDescriptor<Message>() {};

  /** A {@link CoderProvider} for {@link Message proto messages}. */
  private static class ProtoCoderProvider extends CoderProvider {

    @Override
    public <T> Coder<T> coderFor(
        TypeDescriptor<T> typeDescriptor, List<? extends Coder<?>> componentCoders)
        throws CannotProvideCoderException {
      if (!typeDescriptor.isSubtypeOf(MESSAGE_TYPE)) {
        throw new CannotProvideCoderException(
            String.format(
                "Cannot provide %s because %s is not a subclass of %s",
                DynamicProtoCoder.class.getSimpleName(), typeDescriptor, Message.class.getName()));
      }

      @SuppressWarnings("unchecked")
      TypeDescriptor<? extends Message> messageType =
          (TypeDescriptor<? extends Message>) typeDescriptor;
      try {
        @SuppressWarnings("unchecked")
        Coder<T> coder = (Coder<T>) DynamicProtoCoder.of(messageType);
        return coder;
      } catch (IllegalArgumentException e) {
        throw new CannotProvideCoderException(e);
      }
    }
  }
}
