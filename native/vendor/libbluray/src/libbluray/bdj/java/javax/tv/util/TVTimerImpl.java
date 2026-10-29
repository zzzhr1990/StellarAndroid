/*
 * This file is part of libbluray
 * Copyright (C) 2016  VideoLAN
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library. If not, see
 * <http://www.gnu.org/licenses/>.
 */

package javax.tv.util;

import java.util.Timer;
import java.util.TimerTask;
import java.util.Hashtable;

public class TVTimerImpl extends TVTimer
{
    private Timer timer;
    private Hashtable scheduledTasks = new Hashtable(); // TVTimerSpec -> TimerTask

    public TVTimerImpl() {
        timer = new Timer(true); // daemon thread
    }

    public TVTimerSpec scheduleTimerSpec(final TVTimerSpec spec)
            throws TVTimerScheduleFailedException {
        if (spec == null) {
            throw new TVTimerScheduleFailedException("TVTimerSpec is null");
        }

        // Cancel any existing schedule for this spec
        deschedule(spec);

        final TVTimer self = this;
        TimerTask task = new TimerTask() {
            public void run() {
                try {
                    spec.notifyListeners(self);
                } catch (Exception e) {
                    System.err.println("TVTimer: Exception in timer callback: " + e);
                    e.printStackTrace();
                }
                if (!spec.isRepeat()) {
                    scheduledTasks.remove(spec);
                }
            }
        };

        scheduledTasks.put(spec, task);

        long delay = spec.getTime();
        if (spec.isAbsolute()) {
            // Absolute time - calculate delay from current time
            long now = System.currentTimeMillis();
            delay = spec.getTime() - now;
            if (delay < 0) {
                delay = 0; // Fire immediately if time has passed
            }
        }

        try {
            if (spec.isRepeat()) {
                long period = spec.getTime();
                if (period <= 0) {
                    period = 1; // Minimum 1ms period
                }
                timer.scheduleAtFixedRate(task, delay, period);
            } else {
                timer.schedule(task, delay);
            }
        } catch (Exception e) {
            scheduledTasks.remove(spec);
            throw new TVTimerScheduleFailedException("Failed to schedule: " + e.getMessage());
        }

        return spec;
    }

    public long getGranularity() {
        return 1L; // 1 millisecond granularity
    }

    public long getMinRepeatInterval() {
        return 1L; // 1 millisecond minimum repeat
    }

    public void deschedule(TVTimerSpec spec) {
        if (spec != null) {
            TimerTask task = (TimerTask) scheduledTasks.remove(spec);
            if (task != null) {
                task.cancel();
            }
        }
    }

    public void shutdown() {
        timer.cancel();
        scheduledTasks.clear();
    }
}
