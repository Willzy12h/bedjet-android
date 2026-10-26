package io.github.willzy12h.nocturne.core.plan

/** Transient planning reasons; app presentation supplies the localised words. */
enum class NightDurationProblem { OUTSIDE_WINDOW, TOO_LONG }

class NightDurationException(val problem: NightDurationProblem) : PlanException(problem.name)
