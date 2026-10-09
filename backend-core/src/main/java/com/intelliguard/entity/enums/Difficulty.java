package com.intelliguard.entity.enums;

// EASY injections sit clearly outside the normal range; SUBTLE ones overlap it (roughly
// 1.5-3 sigma, or a gradual drift). Set by the simulator when it injects - ground truth only.
public enum Difficulty {
    EASY,
    SUBTLE
}
