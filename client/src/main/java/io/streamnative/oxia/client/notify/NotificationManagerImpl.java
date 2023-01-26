/*
 * Copyright © 2022-2023 StreamNative Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.streamnative.oxia.client.notify;

import static io.grpc.Status.fromThrowable;
import static io.streamnative.oxia.client.api.Notification.KeyModified;
import static lombok.AccessLevel.PACKAGE;

import com.google.common.annotations.VisibleForTesting;
import io.grpc.stub.StreamObserver;
import io.streamnative.oxia.client.api.Notification;
import io.streamnative.oxia.client.api.Notification.KeyCreated;
import io.streamnative.oxia.client.api.Notification.KeyDeleted;
import io.streamnative.oxia.client.grpc.ReceiveWithRecovery;
import io.streamnative.oxia.client.grpc.Receiver;
import io.streamnative.oxia.proto.NotificationBatch;
import io.streamnative.oxia.proto.NotificationsRequest;
import io.streamnative.oxia.proto.OxiaClientGrpc.OxiaClientStub;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@RequiredArgsConstructor(access = PACKAGE)
@Slf4j
public class NotificationManagerImpl implements NotificationManager {

    public static final NotificationManager NullObject =
            new NotificationManager() {
                @Override
                public void start() {
                    // NOOP
                }

                @Override
                public void close() throws Exception {
                    // NOOP
                }
            };

    @NonNull private final Receiver receiver;

    public NotificationManagerImpl(
            @NonNull String serviceAddress,
            @NonNull Function<String, OxiaClientStub> clientSupplier,
            @NonNull Consumer<Notification> notificationCallback) {
        receiver =
                new ReceiveWithRecovery(
                        new GrpcReceiver(serviceAddress, clientSupplier, notificationCallback));
    }

    @Override
    public void close() throws Exception {
        receiver.close();
    }

    @SneakyThrows
    @Override
    public void start() {
        receiver.receive();
        receiver.bootstrap().get();
    }

    @RequiredArgsConstructor(access = PACKAGE)
    @VisibleForTesting
    static class GrpcReceiver implements Receiver {
        @NonNull private final String serviceAddress;
        @NonNull private final Function<String, OxiaClientStub> clientSupplier;
        @NonNull private final Consumer<Notification> notificationCallback;
        @NonNull private final Supplier<CompletableFuture<Void>> streamTerminalSupplier;

        GrpcReceiver(
                @NonNull String serviceAddress,
                @NonNull Function<String, OxiaClientStub> clientSupplier,
                @NonNull Consumer<Notification> notificationCallback) {
            this(serviceAddress, clientSupplier, notificationCallback, CompletableFuture::new);
        }

        public @NonNull CompletableFuture<Void> receive() {
            var terminal = streamTerminalSupplier.get();
            try {
                var observer = new NotificationsObserver(terminal, notificationCallback);
                // Start the stream
                var client = clientSupplier.apply(serviceAddress);
                client.getNotifications(NotificationsRequest.getDefaultInstance(), observer);
            } catch (Exception e) {
                terminal.completeExceptionally(e);
            }
            return terminal;
        }

        @Override
        public @NonNull CompletableFuture<Void> bootstrap() {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void close() {}
    }

    @RequiredArgsConstructor(access = PACKAGE)
    @VisibleForTesting
    static class NotificationsObserver implements StreamObserver<NotificationBatch> {
        @NonNull private final CompletableFuture<Void> streamTerminal;
        @NonNull private final Consumer<Notification> notificationCallback;

        @Override
        public void onNext(NotificationBatch batch) {
            batch.getNotificationsMap().entrySet().stream()
                    .map(
                            e -> {
                                var key = e.getKey();
                                var notice = e.getValue();
                                return switch (notice.getType()) {
                                    case KeyCreated -> new KeyCreated(key, notice.getVersion());
                                    case KeyDeleted -> new KeyDeleted(key, notice.getVersion());
                                    case KeyModified -> new KeyModified(key, notice.getVersion());
                                    default -> null;
                                };
                            })
                    .filter(Objects::nonNull)
                    .forEach(notificationCallback);
        }

        @SneakyThrows
        @Override
        public void onError(Throwable t) {
            log.error("Failed receiving shard assignments - GRPC status: {}", fromThrowable(t));
            // Stream is broken, signal that recovery is necessary
            streamTerminal.completeExceptionally(t);
        }

        @SneakyThrows
        @Override
        public void onCompleted() {
            log.info("Shard Assignment stream completed.");
            // Stream is broken, signal that recovery is necessary
            streamTerminal.complete(null);
        }
    }
}