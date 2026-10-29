/*
 * This file is part of libbluray
 * Copyright (C) 2013  Petri Hintukainen <phintuka@users.sourceforge.net>
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

package org.videolan;

import java.lang.reflect.InvocationTargetException;

public class PortingHelper {

    public static void stopThread(Thread t) {
        try {
            Thread.class.getMethod("stop", new Class[0]).invoke(t, new Object[0]);
        } catch (NoSuchMethodException e) {
            // ignore
        } catch (IllegalAccessException e) {
            // ignore
        } catch (InvocationTargetException e) {
            // ignore
        }
    }

    public static void stopThreadGroup(ThreadGroup t) {
        try {
            ThreadGroup.class.getMethod("stop", new Class[0]).invoke(t, new Object[0]);
        } catch (NoSuchMethodException e) {
            // ignore
        } catch (IllegalAccessException e) {
            // ignore
        } catch (InvocationTargetException e) {
            // ignore
        }
    }

    public static String dumpStack(Thread t) {
        StackTraceElement e[] = t.getStackTrace();
        if (e != null) {
            StringBuffer dump = new StringBuffer();
            for (int i = 0; i < e.length; i++) {
                dump.append("\n\t");
                dump.append(e[i].toString());
            }
            return dump.toString();
        }
        return "";
    }
}
