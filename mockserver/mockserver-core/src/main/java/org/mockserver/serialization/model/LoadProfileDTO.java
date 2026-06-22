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

    public LoadProfileDTO(LoadProfile profile) {
        if (profile != null && profile.getStages() != null) {
            for (LoadStage stage : profile.getStages()) {
                stages.add(new LoadStageDTO(stage));
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
        return new LoadProfile().withStages(builtStages);
    }

    public List<LoadStageDTO> getStages() {
        return stages;
    }

    public LoadProfileDTO setStages(List<LoadStageDTO> stages) {
        this.stages = stages;
        return this;
    }
}
