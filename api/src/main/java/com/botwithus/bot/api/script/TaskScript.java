package com.botwithus.bot.api.script;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.config.ConfigField;
import com.botwithus.bot.api.config.ScriptConfig;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Base class for task-based scripts. Maintains a priority-sorted list of
 * {@link Task} instances and executes the first that validates each loop.
 */
public abstract class TaskScript implements BotScript {

    protected ScriptContext ctx;
    private final List<Task> tasks = new ArrayList<>();

    /**
     * Called during {@link #onStart} to add tasks.
     * Override this to register your tasks.
     */
    protected abstract void setupTasks();

    protected void addTask(Task task) {
        tasks.add(task);
        tasks.sort(Comparator.comparingInt(Task::priority).reversed());
    }

    protected void removeTask(Task task) {
        tasks.remove(task);
    }

    protected List<Task> getTasks() {
        return List.copyOf(tasks);
    }

    @Override
    public void onStart(ScriptContext ctx) {
        this.ctx = ctx;
        setupTasks();
    }

    @Override
    public int onLoop() {
        for (Task task : tasks) {
            if (task.validate()) {
                return task.execute();
            }
        }
        return 600; // default tick delay if no task validates
    }

    @Override
    public void onStop() {}

    @Override
    public List<ConfigField> getConfigFields() { return List.of(); }

    @Override
    public void onConfigUpdate(ScriptConfig config) {}
}
