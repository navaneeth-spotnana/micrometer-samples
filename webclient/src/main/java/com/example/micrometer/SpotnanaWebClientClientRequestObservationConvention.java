package com.example.micrometer;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;

import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.Optional;

import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientRequestObservationContext;
import org.springframework.web.reactive.function.client.DefaultClientRequestObservationConvention;

/** Customises the micrometer tags for WebClient metrics */
public  class SpotnanaWebClientClientRequestObservationConvention
    extends DefaultClientRequestObservationConvention {
    private final String clientName;

    public SpotnanaWebClientClientRequestObservationConvention(final String clientName) {
        this.clientName = clientName;
    }

    @Override
    protected KeyValue clientName(final ClientRequestObservationContext context) {
        // override client name to be name given to the client
        return KeyValue.of("random1", clientName);
    }

    @Override
    public KeyValues getLowCardinalityKeyValues(
        final ClientRequestObservationContext context) {
        var result = super.getLowCardinalityKeyValues(context);

        var host =
            Optional.ofNullable(context.getRequest())
                .map(req -> req.url().getHost())
                .orElse(KeyValue.NONE_VALUE);

        var customTags = getCustomKeyValues(context.getRequest());

        return result.and("random2",host).and(customTags);
    }

    /**
     * Get custom tags passed as attributes in request. Attributes with key starting with
     *  will pe used.
     *
     * @return List of custom tags
     */
    private List<KeyValue> getCustomKeyValues(final ClientRequest request) {
        if (request == null) {
            // request is null at the start of observation. It will be set later and getCustomKeyValues
            // will be called again. So it's safe to return early here.
            return List.of();
        }

        var keyValues = new ArrayList<KeyValue>();
        for (final Entry<String, Object> entry : request.attributes().entrySet()) {
            if (entry.getKey().startsWith("CUSTOM_TAG_PREFIX")) {
                var tagKey = entry.getKey().replace("CUSTOM_TAG_PREFIX", "");
                var tagVal = entry.getValue().toString();
                keyValues.add(KeyValue.of(tagKey, tagVal));
            }
        }
        return keyValues;
    }
}
