package app.taskbaba.core.monk

enum class MonkEvent {
    GREETING_MORNING, GREETING_AFTERNOON, GREETING_EVENING,
    EMPTY_DAY,
    TASK_CREATED, MIT_CHOSEN,
    TASK_DONE, IMPORTANT_DONE, ALL_PRIORITIES_DONE,
    TASK_DUE_SOON, TASK_OVERDUE,
    TASK_POSTPONED, TASK_POSTPONED_REPEAT,
    HEALTH_WATER, HEALTH_EYES, HEALTH_STRETCH, HEALTH_WALK, HEALTH_MEAL, HEALTH_REST,
    HEALTH_DONE, HEALTH_SKIPPED,
    RETURN_FROM_BREAK,
    PRODUCTIVE_DAY, UNPRODUCTIVE_DAY,
    FOCUS_TIP,
}

enum class Tone { FRIENDLY, SAVAGE }

/** Drives the character's expression. */
enum class MonkMood { CALM, HAPPY, STERN, CELEBRATE, SLEEPY }

data class MonkTemplate(val id: String, val event: MonkEvent, val tone: Tone, val mood: MonkMood, val text: String)

/**
 * Original lines. Placeholders: {task}, {count}, {done}, {planned}, {pct}, {overdue}.
 * House rules: tease procrastination, never people; health lines encourage, never shame; respect for
 * traditions — Baba jokes about Jira, not about faith.
 */
object MessageLibrary {
    private val all = mutableListOf<MonkTemplate>()

    private fun lines(event: MonkEvent, tone: Tone, mood: MonkMood, vararg texts: String) {
        texts.forEachIndexed { i, t -> all += MonkTemplate("${event.name.lowercase()}.${tone.name.lowercase()}.$i", event, tone, mood, t) }
    }

    init {
        // ── Greetings ───────────────────────────────────────────────────────────
        lines(MonkEvent.GREETING_MORNING, Tone.FRIENDLY, MonkMood.CALM,
            "Suprabhat, my child. The sun has risen; let your to-do list follow.",
            "Good morning! Chai first, then karma. Your top priority awaits.",
            "A fresh day, a clean slate. Baba suggests starting with the hardest thing while your mind is still calm.",
            "Rise and shine, warrior of the keyboard. Today we conquer {planned} task(s), one at a time.",
            "Morning, beta. Drink a glass of water before you open Slack. The water is kinder.",
            "The early mind is a sharp mind. Pick one task and give it your full attention.",
        )
        lines(MonkEvent.GREETING_MORNING, Tone.SAVAGE, MonkMood.STERN,
            "Good morning. Your tasks woke up before you did. They are waiting, arms crossed.",
            "Suprabhat! {planned} task(s) today. Your snooze button already used up its karma.",
            "Ah, you're here. Your backlog has been meditating on your absence all night.",
            "Morning! Before you scroll anything, remember: the inbox is not a to-do list.",
            "Welcome back to the material world. Your deadlines did not attain moksha overnight.",
        )
        lines(MonkEvent.GREETING_AFTERNOON, Tone.FRIENDLY, MonkMood.CALM,
            "Good afternoon, my child. Half the day is behind you; the better half is ahead.",
            "Post-lunch slump? Stand up, stretch, then pick one small task. Momentum is a gentle river.",
            "Afternoon check-in: {done} of {planned} done. Steady steps reach far mountains.",
            "The afternoon sun is warm and so is Baba's faith in you. Next task?",
        )
        lines(MonkEvent.GREETING_AFTERNOON, Tone.SAVAGE, MonkMood.STERN,
            "Good afternoon. {done} of {planned} done. The other tasks are composing a complaint letter.",
            "It is afternoon. Your morning intentions called — they want to know what happened.",
            "Lunch is digested. Your excuses should be too.",
            "Afternoon already! Time flies when you are reorganising tasks instead of doing them.",
        )
        lines(MonkEvent.GREETING_EVENING, Tone.FRIENDLY, MonkMood.SLEEPY,
            "Good evening, beta. Wrap up gently; tomorrow's you will thank today's you.",
            "Evening has come. Finish one last small thing, then let the laptop rest. You too.",
            "The day is winding down. Whatever is unfinished can wait for sunrise.",
        )
        lines(MonkEvent.GREETING_EVENING, Tone.SAVAGE, MonkMood.STERN,
            "Evening! The perfect time to realise what 'I'll do it later' meant this morning.",
            "Good evening. Your laptop is tired, your eyes are tired, and your tasks are… still there.",
            "Night approaches. Even owls log off eventually, my child.",
        )

        // ── Empty day ───────────────────────────────────────────────────────────
        lines(MonkEvent.EMPTY_DAY, Tone.FRIENDLY, MonkMood.CALM,
            "No tasks planned. A clear sky! Add one intention for today, or simply rest well.",
            "Your list is empty. Peaceful… or suspicious? Add what matters today.",
            "Nothing scheduled. Baba suggests: one important thing, one kind thing, one glass of water.",
        )
        lines(MonkEvent.EMPTY_DAY, Tone.SAVAGE, MonkMood.STERN,
            "Zero tasks. Either you have achieved enlightenment, or you forgot to write them down.",
            "An empty list! Very zen. Very suspicious. Your manager may have opinions.",
            "No tasks today? Even Baba has to sweep the ashram. Add something.",
        )

        // ── Creating & choosing ─────────────────────────────────────────────────
        lines(MonkEvent.TASK_CREATED, Tone.FRIENDLY, MonkMood.HAPPY,
            "\"{task}\" is now on the list. Writing it down is the first step of doing it.",
            "Noted, my child. \"{task}\" has entered the cycle of karma.",
            "Added. A task written is a worry released.",
            "\"{task}\" — Baba has blessed it. Now it only needs you.",
        )
        lines(MonkEvent.TASK_CREATED, Tone.SAVAGE, MonkMood.CALM,
            "\"{task}\" added. Bold of you to assume future-you will have time.",
            "Another task joins the queue. The backlog grows faster than your spiritual enlightenment.",
            "\"{task}\" added. Baba has seen many tasks enter. Few have left.",
            "Noted. Creating tasks is easy, my child. The plot twist is doing them.",
        )
        lines(MonkEvent.MIT_CHOSEN, Tone.FRIENDLY, MonkMood.HAPPY,
            "\"{task}\" is today's Most Important Task. If only this gets done, the day is a success.",
            "Wise choice. Give \"{task}\" your freshest hour.",
            "One task to rule the day: \"{task}\". Baba approves.",
        )
        lines(MonkEvent.MIT_CHOSEN, Tone.SAVAGE, MonkMood.STERN,
            "\"{task}\" is the chosen one. Now please don't abandon it for email.",
            "Most Important Task: \"{task}\". Baba will be watching. Lovingly. Constantly.",
            "You picked \"{task}\". Excellent. Now actually open it.",
        )

        // ── Completing ─────────────────────────────────────────────────────────
        lines(MonkEvent.TASK_DONE, Tone.FRIENDLY, MonkMood.HAPPY,
            "Done! \"{task}\" is complete. Small victories build great temples.",
            "Shabash! One less thing on your mind.",
            "\"{task}\" — finished. Feel that? That's good karma.",
            "Well done, my child. {done} of {planned} for today.",
            "Another one done. The universe nods in quiet approval.",
            "Completed! Take a breath before the next one; you've earned it.",
        )
        lines(MonkEvent.TASK_DONE, Tone.SAVAGE, MonkMood.HAPPY,
            "\"{task}\" done. Baba is shocked. Pleasantly, but shocked.",
            "Look at you, finishing things. Who are you and what have you done with the procrastinator?",
            "Done! See? It took less time than avoiding it did.",
            "{done} of {planned}. Not bad. Not enlightenment either, but not bad.",
            "Task complete. Even karma had to double-check the logs.",
        )
        lines(MonkEvent.IMPORTANT_DONE, Tone.FRIENDLY, MonkMood.CELEBRATE,
            "A big one! \"{task}\" is done. Baba is genuinely proud.",
            "\"{task}\" conquered. The hardest mountain is behind you today.",
            "Magnificent, my child. An important task, finished with grace.",
            "That was the one that mattered. Celebrate with a stretch and some water.",
        )
        lines(MonkEvent.IMPORTANT_DONE, Tone.SAVAGE, MonkMood.CELEBRATE,
            "\"{task}\" is DONE. Baba may need to sit down. Oh wait, he's always sitting.",
            "The big one is finished! Your stand-up update will finally contain a verb in the past tense.",
            "Important task complete. Even your Jira ticket is crying tears of joy.",
            "You did the hard thing first. Who taught you this? Oh right, Baba did.",
        )
        lines(MonkEvent.ALL_PRIORITIES_DONE, Tone.FRIENDLY, MonkMood.CELEBRATE,
            "All of today's priorities are complete. Baba grants you temporary freedom from his lectures.",
            "Every priority done! Go outside, see the sky, drink some water. You've earned peace.",
            "Your priorities are complete, my child. This is what a good day feels like.",
        )
        lines(MonkEvent.ALL_PRIORITIES_DONE, Tone.SAVAGE, MonkMood.CELEBRATE,
            "All priorities done. Baba has nothing sarcastic to say. Enjoy this rare silence.",
            "Priorities: cleared. Karma: impressed. Baba: running out of material.",
            "You finished everything important. Please log off before you invent new problems.",
        )

        // ── Deadlines ──────────────────────────────────────────────────────────
        lines(MonkEvent.TASK_DUE_SOON, Tone.FRIENDLY, MonkMood.CALM,
            "Gentle nudge: \"{task}\" is due soon. A little focus now saves a lot of rush later.",
            "\"{task}\" is coming up. You've got this — start with the first small step.",
            "Heads up, beta: \"{task}\" is due shortly.",
        )
        lines(MonkEvent.TASK_DUE_SOON, Tone.SAVAGE, MonkMood.STERN,
            "\"{task}\" is due soon. The deadline is not a suggestion, my child.",
            "Tick tock. \"{task}\" approaches. Your browser tabs will not finish it for you.",
            "\"{task}\" due shortly. This is your chance to be early for once in this lifetime.",
        )
        lines(MonkEvent.TASK_OVERDUE, Tone.FRIENDLY, MonkMood.CALM,
            "\"{task}\" slipped past its deadline. No guilt — just pick a new time or do it now.",
            "Deadlines move, my child, and so can we. \"{task}\" is waiting whenever you're ready.",
            "\"{task}\" is overdue. Shall we finish it, reschedule it, or let it go?",
            "One task ran late: \"{task}\". A quick decision about it will lighten your mind.",
        )
        lines(MonkEvent.TASK_OVERDUE, Tone.SAVAGE, MonkMood.STERN,
            "\"{task}\" is overdue. Even karma is tracking your backlog now.",
            "\"{task}\" missed its deadline. It is not aging like fine wine. It is aging like milk.",
            "Overdue: \"{task}\". Baba has seen glaciers move faster.",
            "{overdue} overdue task(s). At this point your backlog has its own gravitational field.",
            "\"{task}\" is late. Your future self has filed a formal complaint.",
        )

        // ── Postponing ─────────────────────────────────────────────────────────
        lines(MonkEvent.TASK_POSTPONED, Tone.FRIENDLY, MonkMood.CALM,
            "\"{task}\" moved for later. Rest is fine — just don't let later become never.",
            "Postponed. Sometimes waiting is wisdom. Baba trusts you.",
            "Okay, \"{task}\" can wait a little. What's the one thing you'll do instead?",
        )
        lines(MonkEvent.TASK_POSTPONED, Tone.SAVAGE, MonkMood.STERN,
            "Postponed \"{task}\". A classic move from the ancient art of Tomorrow-yoga.",
            "\"{task}\" snoozed. It will return. They always return.",
            "Later, you say? Baba has heard that mantra many times.",
        )
        lines(MonkEvent.TASK_POSTPONED_REPEAT, Tone.FRIENDLY, MonkMood.CALM,
            "\"{task}\" has been postponed {count} times. Maybe it's too big? Try breaking it into a 10-minute first step.",
            "{count} postponements for \"{task}\". No judgement — but is it still worth doing? Deciding is also progress.",
            "\"{task}\" keeps slipping. What's blocking it, my child? Write that down as the next task.",
        )
        lines(MonkEvent.TASK_POSTPONED_REPEAT, Tone.SAVAGE, MonkMood.STERN,
            "You have postponed \"{task}\" {count} times. Even karma has started tracking your tickets.",
            "{count} postponements! \"{task}\" now qualifies for a long-service award.",
            "\"{task}\", postponed {count} times. At this point it's not a task, it's a family heirloom.",
            "{count} times, my child. Baba admires your consistency. Just not in this direction.",
            "\"{task}\" has been reborn {count} times. Please grant it moksha by finishing it.",
        )

        // ── Health (encouraging, never shaming) ────────────────────────────────
        lines(MonkEvent.HEALTH_WATER, Tone.FRIENDLY, MonkMood.CALM,
            "Time for some water, my child. A hydrated mind is a clear mind.",
            "Paani break! Your brain is mostly water — top it up.",
            "Sip some water. The code will still be there; your focus will be sharper.",
            "Hydration check. One glass now, gratitude later.",
            "Even the Ganga keeps flowing. Have some water, beta.",
        )
        lines(MonkEvent.HEALTH_WATER, Tone.SAVAGE, MonkMood.STERN,
            "O great warrior of the keyboard! Is this productivity or tapasya in the desert? Drink water.",
            "Your water bottle is feeling ignored. Go show it some love.",
            "You remembered to refresh your inbox ten times. Remember water once.",
            "Hydrate, my child. Coffee is not a water substitute, no matter what your mug says.",
            "Water break. Your laptop is plugged in; you deserve the same treatment.",
        )
        lines(MonkEvent.HEALTH_EYES, Tone.FRIENDLY, MonkMood.CALM,
            "Eye break: look at something 20 feet away for 20 seconds. Your eyes will thank you.",
            "Rest your eyes, my child. Gaze out of the window like a wise sage for a moment.",
            "Blink slowly, look far away. The screen can wait 20 seconds.",
        )
        lines(MonkEvent.HEALTH_EYES, Tone.SAVAGE, MonkMood.STERN,
            "Your eyes have been staring at pixels for a while. Look at something that isn't glowing.",
            "Eye break! The far wall misses you. Go stare at it for 20 seconds.",
            "Look away from the screen. Your bugs will not escape in 20 seconds. Probably.",
        )
        lines(MonkEvent.HEALTH_STRETCH, Tone.FRIENDLY, MonkMood.CALM,
            "Stand up and stretch, my child. Reach for the sky like a morning sun salutation.",
            "Stretch break! Roll your shoulders, loosen your neck, breathe deeply.",
            "Your spine has been patient. Give it a gentle stretch.",
        )
        lines(MonkEvent.HEALTH_STRETCH, Tone.SAVAGE, MonkMood.STERN,
            "You've been sitting so long the chair thinks you're furniture. Stand up and stretch!",
            "Stretch time. Your posture is currently shaped like a question mark.",
            "Up, up! Even your laptop gets lifted more often than you do.",
        )
        lines(MonkEvent.HEALTH_WALK, Tone.FRIENDLY, MonkMood.CALM,
            "A short walk clears the mind. Even five minutes counts.",
            "Walk around a little, beta. Great ideas often arrive on foot.",
            "Time to move. Walk to the window, the kitchen, anywhere — just walk.",
        )
        lines(MonkEvent.HEALTH_WALK, Tone.SAVAGE, MonkMood.STERN,
            "Walk break! Your step counter is starting to think it's broken.",
            "Go for a short walk. The only thing that has moved today is your cursor.",
            "Walking meditation time. Bonus karma if you leave your phone behind.",
        )
        lines(MonkEvent.HEALTH_MEAL, Tone.FRIENDLY, MonkMood.HAPPY,
            "Meal time, my child. Eat properly — away from the keyboard if you can.",
            "Time to eat. A good meal is fuel for good work.",
            "Bhojan time! Step away and enjoy your food. The tasks will wait.",
        )
        lines(MonkEvent.HEALTH_MEAL, Tone.SAVAGE, MonkMood.HAPPY,
            "Meal time. No, biscuits over the keyboard do not count as lunch.",
            "Go eat. Your stomach has been sending escalation emails for a while now.",
            "Food break! Even the busiest sage stops for dal-chawal.",
        )
        lines(MonkEvent.HEALTH_REST, Tone.FRIENDLY, MonkMood.SLEEPY,
            "Take a short rest, my child. Close your eyes, breathe in for four, out for six.",
            "Rest break. A calm mind solves problems a tired one cannot.",
            "Pause. Breathe. Make some chai. Then return refreshed.",
        )
        lines(MonkEvent.HEALTH_REST, Tone.SAVAGE, MonkMood.SLEEPY,
            "Your laptop needs charging, your body needs rest, and your deadline needs attention. Choose your next battle wisely — after a break.",
            "Break time. Burnout is not a personality trait, my child.",
            "Rest now. Your brain has more open tabs than your browser.",
        )
        lines(MonkEvent.HEALTH_DONE, Tone.FRIENDLY, MonkMood.HAPPY,
            "Wonderful. Your body says dhanyavaad.",
            "Well done, my child. Caring for yourself is also productive.",
            "Noted with joy. Back to work, refreshed.",
        )
        lines(MonkEvent.HEALTH_DONE, Tone.SAVAGE, MonkMood.HAPPY,
            "Look at you, taking care of yourself. Baba is moved.",
            "Health action complete! Your future physiotherapist is losing a customer.",
            "Done! See, self-care doesn't need a Jira ticket.",
        )
        lines(MonkEvent.HEALTH_SKIPPED, Tone.FRIENDLY, MonkMood.CALM,
            "Skipped — that's okay. You know your body best.",
            "No problem. Baba will check in again later.",
            "Alright, maybe next time. Be gentle with yourself.",
        )

        // ── Returning ──────────────────────────────────────────────────────────
        lines(MonkEvent.RETURN_FROM_BREAK, Tone.FRIENDLY, MonkMood.HAPPY,
            "Welcome back, my child! Refreshed? Your top priority is \"{task}\".",
            "Ah, you return. Let's ease back in with one focused task.",
            "Welcome back. One task at a time — even the universe wasn't built by multitasking.",
        )
        lines(MonkEvent.RETURN_FROM_BREAK, Tone.SAVAGE, MonkMood.STERN,
            "You're back! Your tasks didn't take a break. They waited. Patiently. Judgingly.",
            "Welcome back. \"{task}\" kept your seat warm.",
            "Ah, the wanderer returns. Shall we pretend that break was planned?",
        )

        // ── Day summary ────────────────────────────────────────────────────────
        lines(MonkEvent.PRODUCTIVE_DAY, Tone.FRIENDLY, MonkMood.CELEBRATE,
            "A good day, my child: {done} of {planned} done ({pct}%). Rest well tonight.",
            "{pct}% complete. Today you planted good karma. Tomorrow it grows.",
            "Well done today — {done} task(s) finished. Close the laptop with pride.",
        )
        lines(MonkEvent.PRODUCTIVE_DAY, Tone.SAVAGE, MonkMood.CELEBRATE,
            "{done} of {planned} done. Baba had to check twice. Impressive.",
            "{pct}% today! Keep this up and Baba will have to write new jokes.",
            "A productive day. Enjoy it before tomorrow's backlog wakes up.",
        )
        lines(MonkEvent.UNPRODUCTIVE_DAY, Tone.FRIENDLY, MonkMood.CALM,
            "Today was {done} of {planned}. Some days are like that. Tomorrow, start with one small win.",
            "Not every day is a mountain day. Rest, and begin again at sunrise.",
            "{pct}% today. That's okay, my child. Plan tomorrow's top task tonight and sleep well.",
        )
        lines(MonkEvent.UNPRODUCTIVE_DAY, Tone.SAVAGE, MonkMood.STERN,
            "{done} of {planned} today. Tomorrow's you just inherited quite a dowry of tasks.",
            "{pct}% today. Baba is not angry, just… contemplating.",
            "Today's score: {done}/{planned}. The tasks have formed a union. Negotiate tomorrow.",
        )

        // ── Tips ───────────────────────────────────────────────────────────────
        lines(MonkEvent.FOCUS_TIP, Tone.FRIENDLY, MonkMood.CALM,
            "One task at a time, my child. Even the universe wasn't built by multitasking.",
            "Close the tabs you don't need. A quiet screen makes a quiet mind.",
            "Big task? Do just ten minutes. Starting is the hardest part.",
            "Write down distractions as tasks instead of following them.",
            "Your top priority is \"{task}\". Everything else can wait for twenty minutes.",
        )
        lines(MonkEvent.FOCUS_TIP, Tone.SAVAGE, MonkMood.STERN,
            "Notifications off, my child. Your group chat will survive without you.",
            "Reorganising your task list is not the same as doing it.",
            "\"{task}\" won't finish itself. Baba checked; it's not that kind of magic.",
            "Your Jira backlog is growing faster than your spiritual enlightenment.",
            "Multitasking is just failing at several things at once. Pick one.",
        )
    }

    val templates: List<MonkTemplate> get() = all

    fun forEvent(event: MonkEvent, tone: Tone): List<MonkTemplate> = all.filter { it.event == event && it.tone == tone }
}
