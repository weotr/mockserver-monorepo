package org.mockserver.serialization.model;

import org.mockserver.model.ObjectWithReflectiveEqualsHashCodeToString;
import org.mockserver.slo.SloWindow;

/**
 * DTO for {@link SloWindow}. See {@link SloCriteriaDTO}.
 */
public class SloWindowDTO extends ObjectWithReflectiveEqualsHashCodeToString implements DTO<SloWindow> {

    private SloWindow.Type type;
    private Long fromEpochMillis;
    private Long toEpochMillis;
    private Long lookbackMillis;

    public SloWindowDTO(SloWindow window) {
        if (window != null) {
            type = window.getType();
            fromEpochMillis = window.getFromEpochMillis();
            toEpochMillis = window.getToEpochMillis();
            lookbackMillis = window.getLookbackMillis();
        }
    }

    public SloWindowDTO() {
    }

    public SloWindow buildObject() {
        return new SloWindow()
            .withType(type)
            .withFromEpochMillis(fromEpochMillis)
            .withToEpochMillis(toEpochMillis)
            .withLookbackMillis(lookbackMillis);
    }

    public SloWindow.Type getType() {
        return type;
    }

    public SloWindowDTO setType(SloWindow.Type type) {
        this.type = type;
        return this;
    }

    public Long getFromEpochMillis() {
        return fromEpochMillis;
    }

    public SloWindowDTO setFromEpochMillis(Long fromEpochMillis) {
        this.fromEpochMillis = fromEpochMillis;
        return this;
    }

    public Long getToEpochMillis() {
        return toEpochMillis;
    }

    public SloWindowDTO setToEpochMillis(Long toEpochMillis) {
        this.toEpochMillis = toEpochMillis;
        return this;
    }

    public Long getLookbackMillis() {
        return lookbackMillis;
    }

    public SloWindowDTO setLookbackMillis(Long lookbackMillis) {
        this.lookbackMillis = lookbackMillis;
        return this;
    }
}
