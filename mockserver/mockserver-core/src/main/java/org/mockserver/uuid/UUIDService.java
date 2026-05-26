package org.mockserver.uuid;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.RandomBasedGenerator;

import java.security.SecureRandom;

public class UUIDService {

    private static final RandomBasedGenerator RANDOM_BASED_GENERATOR = Generators.randomBasedGenerator(new SecureRandom());
    public static final String FIXED_UUID_FOR_TESTS = UUIDService.getUUID();
    public static boolean fixedUUID = false;

    public static String getUUID() {
        if (!fixedUUID) {
            return RANDOM_BASED_GENERATOR.generate().toString();
        } else {
            return FIXED_UUID_FOR_TESTS;
        }
    }

}
