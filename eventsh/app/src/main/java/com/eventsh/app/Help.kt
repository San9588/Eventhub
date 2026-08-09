package com.eventsh.app

import android.app.Activity
import android.app.AlertDialog
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.eventsh.app.ui.Maniflow
import com.eventsh.app.ui.Theme

/**
 * HELP - one file that documents every action / concept of Maniflow, shown
 * inside the app via Settings -> HELP. Content is plain English, structured
 * per action with its fields and an example. No external library.
 */
object Help {

    private data class Section(val heading: String, val lines: List<String>)

    private val CONTENT: List<Section> = listOf(
        Section("QUICK START", listOf(
            "Maniflow is built from two things: PROFILES and TASKS.",
            "- A PROFILE watches for an event (a time, an app opening, an SMS, battery level, location, ...).",
            "- When its event fires, the profile runs the linked TASK.",
            "- A TASK is a list of ACTIONS that run top to bottom.",
            "You build actions in the TASKS tab and attach tasks to triggers in the PROFILES tab."
        )),

        Section("VARIABLES", listOf(
            "A variable is a named piece of text. It always has a leading % sign: %NAME.",
            "Create or change one with the Variable Set action; read it anywhere by writing %NAME.",
            "Built-in variables (available in every task):",
            "- %STDOUT  %STDERR  %EXIT  - output of the last Shell Command",
            "- %RAM  %RAM_PCT  %DISK_FREE  - device resources",
            "- %WIFI (ON/OFF)  %SCREEN (ON/OFF)  %AIRPLANE  %NET  %ROOT",
            "- %http_result (HTTP body)  %http_code (HTTP status)",
            "You can write math into a Variable Set value:",
            "# Variable Set:  %total  =  5 * (2 + 3)      ->  %total = 25"
        )),

        Section("ARRAYS", listOf(
            "An array stores a list under one name. Elements are 1-based:",
            "# Array Set:  %list  =  a,b,c,d",
            "This creates %list1=a, %list2=b, %list3=c, %list4=d and %list=a,b,c,d.",
            "Use the numbered name to read one element:",
            "# %list2  -> b      %list4 -> d",
            "Array selectors inside any text:",
            "- %list(#)   -> element count (4)",
            "- %list(-1)  -> last element (d)",
            "- %list(-2)  -> second from the end (c)",
            "Build arrays from ranges or other arrays too:",
            "# Array Set:  %nums  =  1..5        -> 1,2,3,4,5",
            "# Array Set:  %copy  =  %list       -> copies all elements"
        )),

        Section("APPEND", listOf(
            "Variable Set and Array Set can APPEND to an existing value instead of replacing it.",
            "Tick the 'append to existing value' box - a splitter field appears.",
            "Variable Set append joins the old value, then the splitter, then the new value:",
            "# %name = hello,  append 'world' with splitter ' '  ->  %name = hello world",
            "Array Set append adds the new elements to the end of the array:",
            "# %arr = [a,b],  append 'c,d'  ->  %arr = [a,b,c,d]  (%arr3=c, %arr4=d)",
            "A blank splitter means: no separator for variables, comma for arrays."
        )),

        Section("CONDITIONS", listOf(
            "An If (or Wait Until) uses a condition. Compare a variable to a value:",
            "# %vol > 30          numeric comparison",
            "# %name = Ravi        text equality",
            "# %name != Ravi       not equal",
            "# %msg ~ *urgent*     pattern match ( * = any text )",
            "# %msg !~ *spam*      does NOT match",
            "Join several checks with and / or / xor.",
            "Operators: = == != > >= < <= ~ !~"
        )),

        Section("FLOW CONTROL", listOf(
            "IF / ELSE / END IF - branch on a condition (see CONDITIONS).",
            "FOR / END FOR - repeat over a list of values:",
            "# For values: 1..5          -> runs the block 5 times",
            "# For values: a,b,c         -> runs 3 times",
            "# For values: %arr          -> runs once per array element",
            "The current value is stored in the loop variable (default %loop).",
            "WAIT - pause for N seconds (0-86400).",
            "WAIT UNTIL - pause until a condition becomes true, up to a timeout.",
            "GOTO - jump to an action number or label.",
            "A task running Perform Task nests up to 32 levels deep."
        )),

        Section("TERMUX & TOOLS", listOf(
            "TERMUX SCRIPT (script)",
            "  value: Termux task name. Runs ~/.termux/eventsh/<name>.sh via Termux's",
            "  RUN_COMMAND service; current variables are passed as %VAR=value args.",
            "  Needs 'Allow external apps' enabled in Termux settings.",
            "SHELL COMMAND (shell)",
            "  value: a command (sh -c). Runs in-app, saves %STDOUT %STDERR %EXIT.",
            "SEND BROADCAST (intent)",
            "  value: broadcast action (com.pkg.ACTION); extra: extras 'key:value'",
            "  one per line (separated by | or ;); extra2: target package (optional).",
            "NOTIFY (notify)",
            "  value: notification text. Shows a notification titled with the profile.",
            "ROOT COMMAND (root)",
            "  value: a command run with su (root).",
            "FLASH (flash)",
            "  value: text to flash over the screen; extra: duration seconds (0 = short).",
            "SPEAK (speak)",
            "  value: text to speak aloud; extra: pitch (0.5-2); extra2: speech rate (0.5-2).",
            "HTTP REQUEST (http)",
            "  value: URL. extra2: JSON config - method, headers, ctype, body, query,",
            "  timeout, result (result variable), save (file path), redirect.",
            "  Response body is saved to %http_result and status to %http_code."
        )),

        Section("VARIABLE ACTIONS", listOf(
            "VARIABLE SET (var_set)",
            "  value: variable name; extra: value (math and %VAR ok); append optional.",
            "VARIABLE SPLIT (var_split)",
            "  value: variable name; extra: splitter (default ,; blank = per character).",
            "  Splits into %name1, %name2, ...",
            "VARIABLE JOIN (var_join)",
            "  value: base name (%A1..%An); extra: joiner (default ,); extra2: max parts.",
            "  Joins the parts back into the base variable.",
            "VARIABLE QUERY (var_query)",
            "  value: variable to read; extra: target variable to copy into;",
            "  extra2: default when the variable is unset.",
            "ARRAY SET (array_set)",
            "  value: array name; extra: values 'a,b,c | 1..5 | %otherarr'; append optional.",
            "ARRAY PUSH (array_push)",
            "  value: array name; extra: element(s) to add to the end.",
            "ARRAY PROCESS (array_process)",
            "  value: array name; extra: op - reverse | sort | sort desc | unique |",
            "  upper | lower | trim. Rewrites the array in place.",
            "ARRAY POP (array_pop)",
            "  value: array name; extra: index to remove (blank = last);",
            "  extra2: variable to store the popped value in.",
            "ARRAY CLEAR (array_clear)",
            "  value: array name. Deletes every element and the base value."
        )),

        Section("SYSTEM ACTIONS", listOf(
            "SET ALARM (set_alarm)",
            "  value: time 'HH:MM'; extra: alarm label; uses the system clock app",
            "  so sound and snooze live there. Config: vibrate=1, su=1.",
            "CANCEL ALARM (cancel_alarm)",
            "  value: alarm label (blank = all). Without root it tells you to open",
            "  the clock app; with su it cancels directly.",
            "ALARM VOLUME (alarm_volume)",
            "  value: volume 0-15. Sets the alarm stream volume.",
            "WIFI / BLUETOOTH / MOBILE DATA / DISPLAY / AUTO-ROTATE (on + off)",
            "  No fields. These are restricted on newer Android versions - tick",
            "  'Run with su' or grant Shizuku. Without either you get a hint",
            "  notification telling you what to enable."
        )),

        Section("TASK ACTIONS", listOf(
            "PERFORM TASK (task_run)",
            "  value: task name or id. Runs that task now (nests, retried on failure).",
            "TASK STOP (task_stop)",
            "  value: task name or id. Blank = stop the currently running task.",
            "TASK ENABLE / DISABLE (task_enable / task_disable)",
            "  value: task name or id. Enables or disables the task."
        )),

        Section("PROFILE ACTIONS", listOf(
            "PROFILE ENABLE / DISABLE / DELETE (profile_enable / profile_disable / profile_delete)",
            "  value: profile name or id.",
            "  Enable/disables a profile's triggers, or deletes the profile entirely.",
            "  Disabling also cancels its scheduled timers; re-enabling reschedules them."
        ))
    )

    /** Renders the whole help document inside a scrollable dialog. */
    fun show(activity: Activity) {
        val t = Theme.current
        val scroll = ScrollView(activity).apply {
            setBackgroundColor(t.surfaceBg)
            isVerticalScrollBarEnabled = true
        }
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Maniflow.dp(activity, 16), Maniflow.dp(activity, 16), Maniflow.dp(activity, 16), Maniflow.dp(activity, 16))
        }
        scroll.addView(
            root,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        var first = true
        for (section in CONTENT) {
            if (!first) root.addView(Maniflow.divider(activity))
            first = false
            root.addView(heading(activity, section.heading))
            for (line in section.lines) root.addView(blockLine(activity, line))
        }

        AlertDialog.Builder(activity)
            .setTitle("MANIFLOW HELP")
            .setView(scroll)
            .setPositiveButton("CLOSE", null)
            .show()
    }

    private fun heading(activity: Activity, text: String): TextView =
        Maniflow.text(activity, text.uppercase(java.util.Locale.US), 13f, Theme.current.accentPrimary, bold = true).apply {
            letterSpacing = 0.1f
            setPadding(Maniflow.dp(activity, 2), Maniflow.dp(activity, 6), Maniflow.dp(activity, 2), Maniflow.dp(activity, 8))
        }

    private fun blockLine(activity: Activity, line: String): View {
        val t = Theme.current
        return when {
            line.startsWith("# ") -> Maniflow.text(activity, line.substring(2), 12.5f, t.textPrimary).apply {
                setPadding(Maniflow.dp(activity, 8), Maniflow.dp(activity, 6), Maniflow.dp(activity, 8), Maniflow.dp(activity, 6))
                background = Maniflow.rounded(activity, t.cardBg, 8)
            }
            line.startsWith("- ") -> TextView(activity).apply {
                text = "  \u2022  " + line.substring(2)
                textSize = 13.5f
                setTextColor(t.textPrimary)
                setPadding(Maniflow.dp(activity, 4), Maniflow.dp(activity, 1), Maniflow.dp(activity, 4), Maniflow.dp(activity, 1))
            }
            else -> TextView(activity).apply {
                text = line
                textSize = 13.5f
                setTextColor(t.textMuted)
                setPadding(Maniflow.dp(activity, 4), Maniflow.dp(activity, 1), Maniflow.dp(activity, 4), Maniflow.dp(activity, 1))
            }
        }
    }
}
