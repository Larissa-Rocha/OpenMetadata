/*
 *  Copyright 2021 Collate
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.openmetadata.service.events.subscription.gchat;

import static org.openmetadata.schema.api.events.CreateEventSubscription.SubscriptionType.G_CHAT_WEBHOOK;
import static org.openmetadata.service.util.SubscriptionUtil.getClient;
import static org.openmetadata.service.util.SubscriptionUtil.getTargetsForWebhook;
import static org.openmetadata.service.util.SubscriptionUtil.postWebhookMessage;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.List;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.Invocation;
import lombok.extern.slf4j.Slf4j;
import org.openmetadata.common.utils.CommonUtil;
import org.openmetadata.schema.entity.events.EventSubscription;
import org.openmetadata.schema.type.ChangeEvent;
import org.openmetadata.schema.type.Webhook;
import org.openmetadata.service.events.errors.EventPublisherException;
import org.openmetadata.service.events.subscription.SubscriptionPublisher;
import org.openmetadata.service.exception.CatalogExceptionMessage;
import org.openmetadata.service.formatter.decorators.GChatMessageDecorator;
import org.openmetadata.service.formatter.decorators.MessageDecorator;
import org.openmetadata.service.jdbi3.CollectionDAO;
import org.openmetadata.service.resources.events.EventResource;
import org.openmetadata.service.util.JsonUtils;

@Slf4j
public class GChatPublisher extends SubscriptionPublisher {
  private final MessageDecorator<GChatMessage> gChatMessageMessageDecorator = new GChatMessageDecorator();
  private final Webhook webhook;
  private Invocation.Builder target;
  private final Client client;
  private final CollectionDAO daoCollection;

  public GChatPublisher(EventSubscription eventSub, CollectionDAO dao) {
    super(eventSub);
    if (eventSub.getSubscriptionType() == G_CHAT_WEBHOOK) {
      this.daoCollection = dao;
      this.webhook = JsonUtils.convertValue(eventSub.getSubscriptionConfig(), Webhook.class);

      // Build Client
      client = getClient(eventSub.getTimeout(), eventSub.getReadTimeout());

      // Build Target
      if (webhook.getEndpoint() != null) {
        String gChatWebhookURL = webhook.getEndpoint().toString();
        if (!CommonUtil.nullOrEmpty(gChatWebhookURL)) {
          target = client.target(gChatWebhookURL).request();
        }
      }
    } else {
      throw new IllegalArgumentException("GChat Alert Invoked with Illegal Type and Settings.");
    }
  }

  @Override
  protected void onStartDelegate() {
    LOG.info("GChat Webhook publisher started");
  }

  @Override
  protected void onShutdownDelegate() {
    if (null != client) {
      client.close();
    }
  }

  @Override
  protected void sendAlert(EventResource.EventList list) throws JsonProcessingException {
    for (ChangeEvent event : list.getData()) {
      try {
        GChatMessage gchatMessage = gChatMessageMessageDecorator.buildMessage(event);
        List<Invocation.Builder> targets = getTargetsForWebhook(webhook, G_CHAT_WEBHOOK, client, daoCollection, event);
        if (target != null) {
          targets.add(target);
        }
        for (Invocation.Builder actionTarget : targets) {
          postWebhookMessage(this, actionTarget, gchatMessage);
        }
      } catch (Exception e) {
        String message = CatalogExceptionMessage.eventPublisherFailedToPublish(G_CHAT_WEBHOOK, event, e.getMessage());
        LOG.error(message);
        throw new EventPublisherException(message);
      }
    }
  }
}
