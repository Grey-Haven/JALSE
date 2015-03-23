package jalse.actions;

import static jalse.actions.Actions.emptyActionBindings;
import static jalse.actions.Actions.requireNotStopped;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * An abstract {@link MutableActionContext} implementation that supplies all of the non-scheduling
 * methods. This is a convenience class for creating an {@link ActionEngine}.
 *
 * @author Elliot Ford
 *
 * @param <T>
 *            Actor type.
 */
public abstract class AbstractActionContext<T> implements MutableActionContext<T> {

    private final ActionEngine engine;
    private final Action<T> action;
    private final MutableActionBindings bindings;
    private AtomicReference<T> actor;
    private AtomicLong period;
    private AtomicLong initialDelay;

    /**
     * Creates a new AbstractActionContext instance with the supplied engine and action.
     *
     * @param engine
     *            Parent engine.
     * @param action
     *            Action this context is for.
     */
    protected AbstractActionContext(final ActionEngine engine, final Action<T> action) {
	this(engine, action, emptyActionBindings());
    }

    /**
     * Creates a new AbstractActionContext instance with the supplied engine, action and source
     * bindings.
     *
     * @param engine
     *            Parent engine.
     * @param action
     *            Action this context is for.
     * @param sourceBindings
     *            Bindings to shallow copy.
     */
    protected AbstractActionContext(final ActionEngine engine, final Action<T> action,
	    final ActionBindings sourceBindings) {
	this.engine = requireNotStopped(engine);
	this.action = Objects.requireNonNull(action);
	bindings = new DefaultActionBindings(sourceBindings);
	actor = new AtomicReference<>();
	period = new AtomicLong();
	initialDelay = new AtomicLong();
    }

    @Override
    public <S> S get(final String key) {
	return bindings.get(key);
    }

    @Override
    public Action<T> getAction() {
	return action;
    }

    @Override
    public Optional<T> getActor() {
	return Optional.ofNullable(actor.get());
    }

    @Override
    public ActionEngine getEngine() {
	return engine;
    }

    @Override
    public long getInitialDelay(final TimeUnit unit) {
	return unit.convert(initialDelay.get(), TimeUnit.NANOSECONDS);
    }

    @Override
    public long getPeriod(final TimeUnit unit) {
	return unit.convert(period.get(), TimeUnit.NANOSECONDS);
    }

    @Override
    public <S> S put(final String key, final S value) {
	return bindings.put(key, value);
    }

    @Override
    public void putAll(final Map<String, ?> map) {
	bindings.putAll(map);
    }

    @Override
    public <S> S remove(final String key) {
	return bindings.remove(key);
    }

    @Override
    public void setActor(final T actor) {
	this.actor.set(actor);
    }

    @Override
    public void setInitialDelay(final long initialDelay, final TimeUnit unit) {
	this.initialDelay.set(unit.toNanos(initialDelay));
    }

    @Override
    public void setPeriod(final long period, final TimeUnit unit) {
	this.period.set(unit.toNanos(period));
    }

    @Override
    public Map<String, ?> toMap() {
	return bindings.toMap();
    }
}
