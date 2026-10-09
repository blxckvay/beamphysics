package dev.beamphysics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PhysicsTest {
    @Test
    void physicsScenarios() throws Exception {
        assertEquals(0, PhysicsSelfTest.runAll(), "physics scenario checks failed, see output above");
    }
}
