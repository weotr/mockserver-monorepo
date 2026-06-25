package org.mockserver.serialization.model;

import org.mockserver.load.LoadPacing;
import org.mockserver.model.ObjectWithReflectiveEqualsHashCodeToString;

/**
 * @author jamesdbloom
 */
public class LoadPacingDTO extends ObjectWithReflectiveEqualsHashCodeToString implements DTO<LoadPacing> {

    private LoadPacing.Mode mode;
    private double value;

    public LoadPacingDTO(LoadPacing loadPacing) {
        if (loadPacing != null) {
            mode = loadPacing.getMode();
            value = loadPacing.getValue();
        }
    }

    public LoadPacingDTO() {
    }

    public LoadPacing buildObject() {
        return new LoadPacing()
            .withMode(mode != null ? mode : LoadPacing.Mode.NONE)
            .withValue(value);
    }

    public LoadPacing.Mode getMode() {
        return mode;
    }

    public LoadPacingDTO setMode(LoadPacing.Mode mode) {
        this.mode = mode;
        return this;
    }

    public double getValue() {
        return value;
    }

    public LoadPacingDTO setValue(double value) {
        this.value = value;
        return this;
    }
}
