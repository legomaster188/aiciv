package com.aiciv.testutil;

import com.aiciv.nav.Goal;
import com.aiciv.nav.NavStatus;
import com.aiciv.nav.Navigator;
import java.util.ArrayList;
import java.util.List;

public final class FakeNavigator implements Navigator {
    public final List<Goal> goals = new ArrayList<>();
    public NavStatus status = NavStatus.IDLE;
    public int stops;

    @Override public void goTo(Goal goal) { goals.add(goal); status = NavStatus.RUNNING; }
    @Override public NavStatus status() { return status; }
    @Override public void stop() { stops++; status = NavStatus.IDLE; }
}
