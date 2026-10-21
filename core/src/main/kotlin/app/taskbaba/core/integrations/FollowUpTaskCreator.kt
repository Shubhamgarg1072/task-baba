package app.taskbaba.core.integrations

import app.taskbaba.core.model.Task
import app.taskbaba.core.model.TaskDraft

/** How the integrations module turns a notification into a task without depending on task internals. */
fun interface FollowUpTaskCreator {
    fun create(draft: TaskDraft): Result<Task>
}
