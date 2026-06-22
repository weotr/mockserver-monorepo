package org.mockserver.authentication.authorization;

import static org.apache.commons.lang3.StringUtils.isBlank;

/**
 * A coarse control-plane authorization role with a strict hierarchy:
 * {@code ADMIN} ⊇ {@code MUTATE} ⊇ {@code READ}. A principal granted a higher role
 * satisfies every requirement at or below it (an {@code ADMIN} can do anything a
 * {@code MUTATE} or {@code READ} principal can; a {@code MUTATE} principal can also
 * {@code READ}).
 * <p>
 * The role required by an operation is derived from the read/mutate split used by the
 * control plane (reads require {@code READ}; everything else — mutations — requires
 * {@code MUTATE}). {@code ADMIN} is the ceiling for future finer-grained admin-only
 * operations and currently behaves as a strict superset of {@code MUTATE}.
 */
public enum ControlPlaneRole {

    READ(0),
    MUTATE(1),
    ADMIN(2);

    private final int rank;

    ControlPlaneRole(int rank) {
        this.rank = rank;
    }

    /**
     * @return true if a principal granted THIS role is permitted to perform an
     * operation whose required role is {@code required} (i.e. this role's rank is at
     * least the required rank). {@code ADMIN.satisfies(READ)} is true;
     * {@code READ.satisfies(MUTATE)} is false.
     */
    public boolean satisfies(ControlPlaneRole required) {
        return required != null && this.rank >= required.rank;
    }

    /**
     * Parses a role name case-insensitively (read / mutate / admin), tolerating
     * surrounding whitespace. Returns null for a blank or unrecognised value so callers
     * can fail closed (an unmappable role grants nothing).
     */
    public static ControlPlaneRole parse(String value) {
        if (isBlank(value)) {
            return null;
        }
        switch (value.trim().toLowerCase()) {
            case "read":
                return READ;
            case "mutate":
                return MUTATE;
            case "admin":
                return ADMIN;
            default:
                return null;
        }
    }
}
