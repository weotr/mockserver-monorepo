package org.mockserver.serialization.model;

import org.mockserver.load.LoadProfile;
import org.mockserver.load.LoadStage;
import org.mockserver.model.ObjectWithReflectiveEqualsHashCodeToString;

import java.util.ArrayList;
import java.util.List;

/**
 * @author jamesdbloom
 */
public class LoadProfileDTO extends ObjectWithReflectiveEqualsHashCodeToString implements DTO<LoadProfile> {

    private List<LoadStageDTO> stages = new ArrayList<>();
    private LoadShapeDTO shape;

    public LoadProfileDTO(LoadProfile profile) {
        if (profile != null) {
            // Serialize the RAW explicit stages only, never the shape expansion — emitting both would
            // double-apply on re-read (a shape plus the stages it already expands to). With NON_EMPTY
            // serialization an empty stages list is omitted when only a shape is set.
            if (profile.getRawStages() != null) {
                for (LoadStage stage : profile.getRawStages()) {
                    stages.add(new LoadStageDTO(stage));
                }
            }
            if (profile.getShape() != null) {
                shape = new LoadShapeDTO(profile.getShape());
            }
        }
    }

    public LoadProfileDTO() {
    }

    public LoadProfile buildObject() {
        List<LoadStage> builtStages = new ArrayList<>();
        if (stages != null) {
            for (LoadStageDTO stage : stages) {
                builtStages.add(stage.buildObject());
            }
        }
        LoadProfile profile = new LoadProfile().withStages(builtStages);
        if (shape != null) {
            profile.withShape(shape.buildObject());
        }
        return profile;
    }

    public List<LoadStageDTO> getStages() {
        return stages;
    }

    public LoadProfileDTO setStages(List<LoadStageDTO> stages) {
        this.stages = stages;
        return this;
    }

    public LoadShapeDTO getShape() {
        return shape;
    }

    public LoadProfileDTO setShape(LoadShapeDTO shape) {
        this.shape = shape;
        return this;
    }
}
