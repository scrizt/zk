/* Configuration.java

{{IS_NOTE
	Purpose:
		
	Description:
		
	History:
		Sun Mar 26 16:06:56     2006, Created by tomyeh
}}IS_NOTE

Copyright (C) 2006 Potix Corporation. All Rights Reserved.

{{IS_RIGHT
	This program is distributed under GPL Version 2.0 in the hope that
	it will be useful, but WITHOUT ANY WARRANTY.
}}IS_RIGHT
*/
package org.zkoss.zk.ui.util;

import java.util.Iterator;
import java.util.List;
import java.util.LinkedList;
import java.util.Collections;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.HashSet;

import org.zkoss.lang.Classes;
import org.zkoss.lang.PotentialDeadLockException;
import org.zkoss.lang.Exceptions;
import org.zkoss.util.WaitLock;
import org.zkoss.util.logging.Log;
import org.zkoss.xel.ExpressionFactory;
import org.zkoss.xel.Expressions;

import org.zkoss.zk.ui.Component;
import org.zkoss.zk.ui.WebApp;
import org.zkoss.zk.ui.Session;
import org.zkoss.zk.ui.Desktop;
import org.zkoss.zk.ui.Execution;
import org.zkoss.zk.ui.Executions;
import org.zkoss.zk.ui.Richlet;
import org.zkoss.zk.ui.UiException;
import org.zkoss.zk.ui.event.Event;
import org.zkoss.zk.ui.event.EventThreadInit;
import org.zkoss.zk.ui.event.EventThreadCleanup;
import org.zkoss.zk.ui.event.EventThreadSuspend;
import org.zkoss.zk.ui.event.EventThreadResume;
import org.zkoss.zk.ui.sys.WebAppCtrl;
import org.zkoss.zk.ui.sys.UiEngine;
import org.zkoss.zk.ui.sys.DesktopCacheProvider;
import org.zkoss.zk.ui.sys.UiFactory;
import org.zkoss.zk.ui.sys.FailoverManager;
import org.zkoss.zk.ui.sys.IdGenerator;
import org.zkoss.zk.ui.impl.RichletConfigImpl;
import org.zkoss.zk.ui.impl.EventInterceptors;
import org.zkoss.zk.device.Devices;

/**
 * The ZK configuration.
 *
 * <p>To retrieve the current configuration, use
 * {@link org.zkoss.zk.ui.WebApp#getConfiguration}.
 *
 * <p>Note: A {@link Configuration} instance can be assigned to at most one
 * {@link WebApp} instance.
 *
 * @author tomyeh
 */
public class Configuration {
	private static final Log log = Log.lookup(Configuration.class);

	private static final String PROP_EXPRESS_FACTORY
		= "org.zkoss.xel.ExpressionFactory.class";

	private WebApp _wapp;
	/** List of classes. */
	private final List
		_evtInits = new LinkedList(), _evtCleans = new LinkedList(),
		_evtSusps = new LinkedList(), _evtResus = new LinkedList(),
		_appInits = new LinkedList(), _appCleans = new LinkedList(),
		_sessInits = new LinkedList(), _sessCleans = new LinkedList(),
		_dtInits = new LinkedList(), _dtCleans = new LinkedList(),
		_execInits = new LinkedList(), _execCleans = new LinkedList();
	/** List of objects. */
	private final List
		_uriIntcps = new LinkedList(), _reqIntcps = new LinkedList();
	private final Map _prefs  = Collections.synchronizedMap(new HashMap()),
		_errURIs  = Collections.synchronizedMap(new HashMap());
	/** Map(String name, [Class richlet, Map params] or Richilet richlet). */
	private final Map _richlets = new HashMap();
	/** Map(String path, [String name, boolean wildcard]). */
	private final Map _richletmaps = new HashMap();
	/** Map(String deviceType, List(ErrorPage)). */
	private final Map _errpgs = new HashMap(3);
	private Monitor _monitor;
	private PerformanceMeter _pfmeter;
	private final List _themeURIs = new LinkedList();
	private transient String[] _roThemeURIs = new String[0];
	private ThemeProvider _themeProvider;
	/** A set of disabled theme URIs. */
	private Set _disThemeURIs;
	private Class _wappcls, _uiengcls, _dcpcls, _uiftycls,
		_failmancls, _idgencls;
	private int _dtTimeout = 3600, _dtMax = 10, _sessTimeout = 0,
		_sparThdMax = 100, _suspThdMax = -1,
		_maxUploadSize = 5120, _maxProcTime = 3000,
		_promptDelay = 900, _tooltipDelay = 800;
	private String _charsetResp = "UTF-8", _charsetUpload = "UTF-8";
	private CharsetFinder _charsetFinderUpload;
	/** The event interceptors. */
	private final EventInterceptors _eis = new EventInterceptors();
	/** whether to use the event processing thread. */
	private boolean _useEvtThd = true;
	/** keep-across-visits. */
	private boolean _keepDesktop;
	/** Whether to disable components that don't belong to the modal window. */
	private boolean _disableBehindModal = true;
	/** Whether to keep the session alive when receiving onTimer.
	 */
	private boolean _timerKeepAlive;

	/** Contructor.
	 */
	public Configuration() {
		_errURIs.put(new Integer(302), "");
		_errURIs.put(new Integer(401), "");
		_errURIs.put(new Integer(403), "");
	}

	/** Returns the Web application that this configuration belongs to,
	 * or null if it is not associated yet.
	 */
	public WebApp getWebApp() {
		return _wapp;
	}
	/** Associates it with a web application.
	 */
	public void setWebApp(WebApp wapp) {
		_wapp = wapp;
	}

	/** Adds a listener class.
	 *
	 * @param klass the listener class must implement at least one of
	 * {@link Monitor}, {@link PerformanceMeter}, {@link EventThreadInit},
	 * {@link EventThreadCleanup}, {@link EventThreadSuspend},
	 * {@link EventThreadResume}, {@link WebAppInit}, {@link WebAppCleanup},
	 * {@link SessionInit}, {@link SessionCleanup}, {@link DesktopInit},
	 * {@link DesktopCleanup}, {@link ExecutionInit}, {@link ExecutionCleanup},
	 * {@link URIInterceptor}, {@link RequestInterceptor}, {@link EventInterceptor}
	 * interfaces.
	 */
	public void addListener(Class klass) throws Exception {
		boolean added = false;
		if (Monitor.class.isAssignableFrom(klass)) {
			if (_monitor != null)
				throw new UiException("Monitor listener can be assigned only once");
			_monitor = (Monitor)klass.newInstance();
			added = true;
		}
		if (PerformanceMeter.class.isAssignableFrom(klass)) {
			if (_pfmeter != null)
				throw new UiException("PerformanceMeter listener can be assigned only once");
			_pfmeter = (PerformanceMeter)klass.newInstance();
			added = true;
		}

		if (EventThreadInit.class.isAssignableFrom(klass)) {
			synchronized (_evtInits) {
				_evtInits.add(klass);
			}
			added = true;
		}
		if (EventThreadCleanup.class.isAssignableFrom(klass)) {
			synchronized (_evtCleans) {
				_evtCleans.add(klass);
			}
			added = true;
		}
		if (EventThreadSuspend.class.isAssignableFrom(klass)) {
			synchronized (_evtSusps) {
				_evtSusps.add(klass);
			}
			added = true;
		}
		if (EventThreadResume.class.isAssignableFrom(klass)) {
			synchronized (_evtResus) {
				_evtResus.add(klass);
			}
			added = true;
		}

		if (WebAppInit.class.isAssignableFrom(klass)) {
			synchronized (_appInits) {
				_appInits.add(klass);
			}
			added = true;
		}
		if (WebAppCleanup.class.isAssignableFrom(klass)) {
			synchronized (_appCleans) {
				_appCleans.add(klass);
			}
			added = true;
		}

		if (SessionInit.class.isAssignableFrom(klass)) {
			synchronized (_sessInits) {
				_sessInits.add(klass);
			}
			added = true;
		}
		if (SessionCleanup.class.isAssignableFrom(klass)) {
			synchronized (_sessCleans) {
				_sessCleans.add(klass);
			}
			added = true;
		}

		if (DesktopInit.class.isAssignableFrom(klass)) {
			synchronized (_dtInits) {
				_dtInits.add(klass);
			}
			added = true;
		}
		if (DesktopCleanup.class.isAssignableFrom(klass)) {
			synchronized (_dtCleans) {
				_dtCleans.add(klass);
			}
			added = true;
		}

		if (ExecutionInit.class.isAssignableFrom(klass)) {
			synchronized (_execInits) {
				_execInits.add(klass);
			}
			added = true;
		}
		if (ExecutionCleanup.class.isAssignableFrom(klass)) {
			synchronized (_execCleans) {
				_execCleans.add(klass);
			}
			added = true;
		}
		if (URIInterceptor.class.isAssignableFrom(klass)) {
			try {
				final Object obj = klass.newInstance();
				synchronized (_uriIntcps) {
					_uriIntcps.add(obj);
				}
			} catch (Throwable ex) {
				log.error("Failed to instantiate "+klass, ex);
			}
			added = true;
		}
		if (RequestInterceptor.class.isAssignableFrom(klass)) {
			try {
				final Object obj = klass.newInstance();
				synchronized (_reqIntcps) {
					_reqIntcps.add(obj);
				}
			} catch (Throwable ex) {
				log.error("Failed to instantiate "+klass, ex);
			}
			added = true;
		}
		if (EventInterceptor.class.isAssignableFrom(klass)) {
			try {
				_eis.addEventInterceptor((EventInterceptor)klass.newInstance());
			} catch (Throwable ex) {
				log.error("Failed to instantiate "+klass, ex);
			}
			added = true;
		}

		if (!added)
			throw new UiException("Unknown listener: "+klass);
	}
	/** Removes a listener class.
	 */
	public void removeListener(Class klass) {
		synchronized (_evtInits) {
			_evtInits.remove(klass);
		}
		synchronized (_evtCleans) {
			_evtCleans.remove(klass);
		}
		synchronized (_evtSusps) {
			_evtSusps.remove(klass);
		}
		synchronized (_evtResus) {
			_evtResus.remove(klass);
		}

		synchronized (_appInits) {
			_appInits.remove(klass);
		}
		synchronized (_appCleans) {
			_appCleans.remove(klass);
		}
		synchronized (_sessInits) {
			_sessInits.remove(klass);
		}
		synchronized (_sessCleans) {
			_sessCleans.remove(klass);
		}
		synchronized (_dtInits) {
			_dtInits.remove(klass);
		}
		synchronized (_dtCleans) {
			_dtCleans.remove(klass);
		}
		synchronized (_execInits) {
			_execInits.remove(klass);
		}
		synchronized (_execCleans) {
			_execCleans.remove(klass);
		}
		synchronized (_uriIntcps) {
			for (Iterator it = _uriIntcps.iterator(); it.hasNext();) {
				final Object obj = it.next();
				if (obj.getClass().equals(klass))
					it.remove();
			}
		}
		synchronized (_reqIntcps) {
			for (Iterator it = _reqIntcps.iterator(); it.hasNext();) {
				final Object obj = it.next();
				if (obj.getClass().equals(klass))
					it.remove();
			}
		}

		_eis.removeEventInterceptor(klass);
	}

	/** Contructs a list of {@link EventThreadInit} instances and invokes
	 * {@link EventThreadInit#prepare} for
	 * each relevant listener registered by {@link #addListener}.
	 *
	 * <p>Used only internally (by {@link UiEngine} before starting an event
	 * processing thread).
	 *
	 * @exception UiException to prevent a thread from being processed
	 * if {@link EventThreadInit#prepare} throws an exception
	 * @return a list of {@link EventThreadInit} instances that are
	 * constructed in this method (and their {@link EventThreadInit#prepare}
	 * are called successfully), or null.
	 */
	public List newEventThreadInits(Component comp, Event evt)
	throws UiException {
		if (_evtInits.isEmpty()) return null;
			//it is OK to test LinkedList.isEmpty without synchronized

		final List inits = new LinkedList();
		synchronized (_evtInits) {
			for (Iterator it = _evtInits.iterator(); it.hasNext();) {
				final Class klass = (Class)it.next();
				try {
					final EventThreadInit init =
						(EventThreadInit)klass.newInstance();
					init.prepare(comp, evt);
					inits.add(init);
				} catch (Throwable ex) {
					throw UiException.Aide.wrap(ex);
					//Don't intercept; to prevent the event being processed
				}
			}
		}
		return inits;
	}
	/** Invokes {@link EventThreadInit#init} for each instance returned
	 * by {@link #newEventThreadInits}.
	 *
 	 * <p>Used only internally.
	 *
	 * @param inits a list of {@link EventThreadInit} instances returned from
	 * {@link #newEventThreadInits}, or null if no instance at all.
	 * @param comp the component which the event is targeting
	 * @param evt the event to process
	 * @exception UiException to prevent a thread from being processed
	 * if {@link EventThreadInit#prepare} throws an exception
	 * @return false if you want to ignore the event, i.e., not to proceed
	 * any event processing for the specified event (evt).
	 */
	public boolean invokeEventThreadInits(List inits, Component comp, Event evt) 
	throws UiException {
		if (inits == null || inits.isEmpty()) return true; //not to ignore

		for (Iterator it = inits.iterator(); it.hasNext();) {
			final EventThreadInit fn = (EventThreadInit)it.next();
			try {
				try {
					if (!fn.init(comp, evt))
						return false; //ignore the event
				} catch (AbstractMethodError ex) { //backward compatible prior to 3.0
					fn.getClass().getMethod(
						"init", new Class[] {Component.class, Event.class})
					  .invoke(fn, new Object[] {comp, evt});
				}
			} catch (Throwable ex) {
				throw UiException.Aide.wrap(ex);
				//Don't intercept; to prevent the event being processed
			}
		}
		return true;
	}
	/** Invokes {@link EventThreadCleanup#cleanup} for each relevant
	 * listener registered by {@link #addListener}.
	 *
 	 * <p>Used only internally.
	 *
	 * <p>An instance of {@link EventThreadCleanup} is constructed first,
	 * and then invoke {@link EventThreadCleanup#cleanup}.
	 *
	 * <p>It never throws an exception but logs and adds it to the errs argument,
	 * if not null.
	 *
	 * @param comp the component which the event is targeting
	 * @param evt the event to process
	 * @param errs a list of exceptions (java.lang.Throwable) if any exception
	 * occured before this method is called, or null if no exeption at all.
	 * Note: you can manipulate the list directly to add or clean up exceptions.
	 * For example, if exceptions are fixed correctly, you can call errs.clear()
	 * such that no error message will be displayed at the client.
	 * @return a list of {@link EventThreadCleanup}, or null
	 */
	public List newEventThreadCleanups(Component comp, Event evt, List errs) {
		if (_evtCleans.isEmpty()) return null;
			//it is OK to test LinkedList.isEmpty without synchronized

		final List cleanups = new LinkedList();
		synchronized (_evtCleans) {
			for (Iterator it = _evtCleans.iterator(); it.hasNext();) {
				final Class klass = (Class)it.next();
				try {
					final EventThreadCleanup cleanup =
						(EventThreadCleanup)klass.newInstance();
					cleanup.cleanup(comp, evt, errs);
					cleanups.add(cleanup);
				} catch (Throwable t) {
					if (errs != null) errs.add(t);
					log.error("Failed to invoke "+klass, t);
				}
			}
		}
		return cleanups;
	}
	/** Invoke {@link EventThreadCleanup#complete} for each instance returned by
	 * {@link #newEventThreadCleanups}.
	 *
 	 * <p>Used only internally.
	 *
	 * <p>It never throws an exception but logs and adds it to the errs argument,
	 * if not null.
	 *
	 * @param cleanups a list of {@link EventThreadCleanup} instances returned from
	 * {@link #newEventThreadCleanups}, or null if no instance at all.
	 * @param errs used to hold the exceptions that are thrown by
	 * {@link EventThreadCleanup#complete}.
	 * If null, all exceptions are ignored (but logged).
	 */
	public void invokeEventThreadCompletes(List cleanups, Component comp, Event evt,
	List errs) {
		if (cleanups == null || cleanups.isEmpty()) return;

		for (Iterator it = cleanups.iterator(); it.hasNext();) {
			final EventThreadCleanup fn = (EventThreadCleanup)it.next();
			try {
				fn.complete(comp, evt);
			} catch (Throwable ex) {
				if (errs != null) errs.add(ex);
				log.error("Failed to invoke "+fn, ex);
			}
		}
	}

	/** Constructs a list of {@link EventThreadSuspend} instances and invokes
	 * {@link EventThreadSuspend#beforeSuspend} for each relevant
	 * listener registered by {@link #addListener}.
	 *
 	 * <p>Used only internally.
 	 *
	 * <p>Note: caller shall execute in the event processing thread.
	 *
	 * @param comp the component which the event is targeting
	 * @param evt the event to process
	 * @param obj which object that {@link Executions#wait}
	 * is called with.
	 * @exception UiException to prevent a thread from suspending
	 * @return a list of {@link EventThreadSuspend}, or null
	 */
	public List newEventThreadSuspends(Component comp, Event evt, Object obj) {
		if (_evtSusps.isEmpty()) return null;
			//it is OK to test LinkedList.isEmpty without synchronized

		final List suspends = new LinkedList();
		synchronized (_evtSusps) {
			for (Iterator it = _evtSusps.iterator(); it.hasNext();) {
				final Class klass = (Class)it.next();
				try {
					final EventThreadSuspend suspend =
						(EventThreadSuspend)klass.newInstance();
					suspend.beforeSuspend(comp, evt, obj);
					suspends.add(suspend);
				} catch (Throwable ex) {
					throw UiException.Aide.wrap(ex);
					//Don't intercept; to prevent the event being suspended
				}
			}
		}
		return suspends;
	}
	/** Invokes {@link EventThreadSuspend#afterSuspend} for each relevant
	 * listener registered by {@link #addListener}.
	 * Unlike {@link #invokeEventThreadSuspends}, caller shall execute in
	 * the main thread (aka, servlet thread).
	 *
 	 * <p>Used only internally.
	 *
	 * <p>Unlike {@link #invokeEventThreadSuspends}, exceptions are logged
	 * and ignored.
	 *
	 * @param suspends a list of {@link EventThreadSuspend} instances returned
	 * from {@link #newEventThreadSuspends}, or null if no instance at all.
	 * @param comp the component which the event is targeting
	 * @param evt the event to process
	 */
	public void invokeEventThreadSuspends(List suspends, Component comp, Event evt)
	throws UiException {
		if (suspends == null || suspends.isEmpty()) return;

		for (Iterator it = suspends.iterator(); it.hasNext();) {
			final EventThreadSuspend fn = (EventThreadSuspend)it.next();
			try {
				fn.afterSuspend(comp, evt);
			} catch (Throwable ex) {
				log.error("Failed to invoke "+fn+" after suspended", ex);
			}
		}
	}

	/** Contructs a list of {@link EventThreadResume} instances and invokes
	 * {@link EventThreadResume#beforeResume} for each relevant
	 * listener registered by {@link #addListener}.
	 *
	 * <p>Used only internally (by {@link UiEngine} when resuming a suspended event
	 * thread).
	 * Notice: it executes in the main thread (i.e., the servlet thread).
	 *
	 * @param comp the component which the event is targeting
	 * @param evt the event to process
	 * @exception UiException to prevent a thread from being resumed
	 * if {@link EventThreadResume#beforeResume} throws an exception
	 * @return a list of {@link EventThreadResume} instances that are constructed
	 * in this method (and their {@link EventThreadResume#beforeResume}
	 * are called successfully), or null.
	 */
	public List newEventThreadResumes(Component comp, Event evt)
	throws UiException {
		if (_evtResus.isEmpty()) return null;
			//it is OK to test LinkedList.isEmpty without synchronized

		final List resumes = new LinkedList();
		synchronized (_evtResus) {
			for (Iterator it = _evtResus.iterator(); it.hasNext();) {
				final Class klass = (Class)it.next();
				try {
					final EventThreadResume resume =
						(EventThreadResume)klass.newInstance();
					resume.beforeResume(comp, evt);
					resumes.add(resume);
				} catch (Throwable ex) {
					throw UiException.Aide.wrap(ex);
					//Don't intercept; to prevent the event being resumed
				}
			}
		}
		return resumes;
	}
	/** Invokes {@link EventThreadResume#afterResume} for each instance returned
	 * by {@link #newEventThreadResumes}.
	 *
 	 * <p>Used only internally.
	 *
	 * <p>It never throws an exception but logs and adds it to the errs argument,
	 * if not null.
	 *
	 * @param resumes a list of {@link EventThreadResume} instances returned from
	 * {@link #newEventThreadResumes}, or null if no instance at all.
	 * @param comp the component which the event is targeting
	 * @param evt the event to process
	 * If null, all exceptions are ignored (but logged)
	 */
	public void invokeEventThreadResumes(List resumes, Component comp, Event evt)
	throws UiException {
		if (resumes == null || resumes.isEmpty()) return;

		for (Iterator it = resumes.iterator(); it.hasNext();) {
			final EventThreadResume fn = (EventThreadResume)it.next();
			try {
				fn.afterResume(comp, evt);
			} catch (Throwable ex) {
				throw UiException.Aide.wrap(ex);
			}
		}
	}
	/** Invokes {@link EventThreadResume#abortResume} for each relevant
	 * listener registered by {@link #addListener}.
	 *
 	 * <p>Used only internally.
	 *
	 * <p>An instance of {@link EventThreadResume} is constructed first,
	 * and then invoke {@link EventThreadResume#abortResume}.
	 *
	 * <p>It never throws an exception but logging.
	 *
	 * @param comp the component which the event is targeting
	 * @param evt the event to process
	 */
	public void invokeEventThreadResumeAborts(Component comp, Event evt) {
		if (_evtResus.isEmpty()) return;
			//it is OK to test LinkedList.isEmpty without synchronized

		synchronized (_evtResus) {
			for (Iterator it = _evtResus.iterator(); it.hasNext();) {
				final Class klass = (Class)it.next();
				try {
					((EventThreadResume)klass.newInstance())
						.abortResume(comp, evt);
				} catch (Throwable ex) {
					log.error("Failed to invoke "+klass+" for aborting", ex);
				}
			}
		}
	}

	/** Invokes {@link WebAppInit#init} for each relevant
	 * listener registered by {@link #addListener}.
	 *
 	 * <p>Used only internally.
	 *
	 * <p>An instance of {@link WebAppInit} is constructed first,
	 * and then invoke {@link WebAppInit#init}.
	 *
	 * <p>Unlike {@link #invokeWebAppInits}, it doesn't throw any exceptions.
	 * Rather, it only logs them.
	 */
	public void invokeWebAppInits() throws UiException {
		if (_appInits.isEmpty()) return;
			//it is OK to test LinkedList.isEmpty without synchronized

		synchronized (_appInits) {
			for (Iterator it = _appInits.iterator(); it.hasNext();) {
				final Class klass = (Class)it.next();
				try {
					((WebAppInit)klass.newInstance()).init(_wapp);
				} catch (Throwable ex) {
					log.error("Failed to invoke "+klass, ex);
				}
			}
		}
	}
	/** Invokes {@link WebAppCleanup#cleanup} for each relevant
	 * listener registered by {@link #addListener}.
	 *
 	 * <p>Used only internally.
	 *
	 * <p>An instance of {@link WebAppCleanup} is constructed first,
	 * and then invoke {@link WebAppCleanup#cleanup}.
	 *
	 * <p>It never throws an exception.
	 */
	public void invokeWebAppCleanups() {
		if (_appCleans.isEmpty()) return;
			//it is OK to test LinkedList.isEmpty without synchronized

		synchronized (_appCleans) {
			for (Iterator it = _appCleans.iterator(); it.hasNext();) {
				final Class klass = (Class)it.next();
				try {
					((WebAppCleanup)klass.newInstance()).cleanup(_wapp);
				} catch (Throwable ex) {
					log.error("Failed to invoke "+klass, ex);
				}
			}
		}
	}

	/** Invokes {@link SessionInit#init} for each relevant
	 * listener registered by {@link #addListener}.
	 *
 	 * <p>Used only internally.
	 *
	 * <p>An instance of {@link SessionInit} is constructed first,
	 * and then invoke {@link SessionInit#init}.
	 *
	 * @param sess the session that is created
	 * @exception UiException to prevent a session from being created
	 */
	public void invokeSessionInits(Session sess)
	throws UiException {
		if (_sessInits.isEmpty()) return;
			//it is OK to test LinkedList.isEmpty without synchronized

		synchronized (_sessInits) {
			for (Iterator it = _sessInits.iterator(); it.hasNext();) {
				final Class klass = (Class)it.next();
				try {
					((SessionInit)klass.newInstance()).init(sess);
				} catch (Throwable ex) {
					throw UiException.Aide.wrap(ex);
					//Don't intercept; to prevent the creation of a session
				}
			}
		}
	}
	/** Invokes {@link SessionCleanup#cleanup} for each relevant
	 * listener registered by {@link #addListener}.
	 *
 	 * <p>Used only internally.
	 *
	 * <p>An instance of {@link SessionCleanup} is constructed first,
	 * and then invoke {@link SessionCleanup#cleanup}.
	 *
	 * <p>It never throws an exception.
	 *
	 * @param sess the session that is being destroyed
	 */
	public void invokeSessionCleanups(Session sess) {
		if (_sessCleans.isEmpty()) return;
			//it is OK to test LinkedList.isEmpty without synchronized

		synchronized (_sessCleans) {
			for (Iterator it = _sessCleans.iterator(); it.hasNext();) {
				final Class klass = (Class)it.next();
				try {
					((SessionCleanup)klass.newInstance()).cleanup(sess);
				} catch (Throwable ex) {
					log.error("Failed to invoke "+klass, ex);
				}
			}
		}
	}

	/** Invokes {@link DesktopInit#init} for each relevant
	 * listener registered by {@link #addListener}.
	 *
 	 * <p>Used only internally.
	 *
	 * <p>An instance of {@link DesktopInit} is constructed first,
	 * and then invoke {@link DesktopInit#init}.
	 *
	 * @param desktop the desktop that is created
	 * @exception UiException to prevent a desktop from being created
	 */
	public void invokeDesktopInits(Desktop desktop)
	throws UiException {
		if (_dtInits.isEmpty()) return;
			//it is OK to test LinkedList.isEmpty without synchronized

		synchronized (_dtInits) {
			for (Iterator it = _dtInits.iterator(); it.hasNext();) {
				final Class klass = (Class)it.next();
				try {
					((DesktopInit)klass.newInstance()).init(desktop);
				} catch (Throwable ex) {
					throw UiException.Aide.wrap(ex);
					//Don't intercept; to prevent the creation of a session
				}
			}
		}
	}
	/** Invokes {@link DesktopCleanup#cleanup} for each relevant
	 * listener registered by {@link #addListener}.
	 *
 	 * <p>Used only internally.
	 *
	 * <p>An instance of {@link DesktopCleanup} is constructed first,
	 * and then invoke {@link DesktopCleanup#cleanup}.
	 *
	 * <p>It never throws an exception.
	 *
	 * @param desktop the desktop that is being destroyed
	 */
	public void invokeDesktopCleanups(Desktop desktop) {
		if (_dtCleans.isEmpty()) return;
			//it is OK to test LinkedList.isEmpty without synchronized

		synchronized (_dtCleans) {
			for (Iterator it = _dtCleans.iterator(); it.hasNext();) {
				final Class klass = (Class)it.next();
				try {
					((DesktopCleanup)klass.newInstance()).cleanup(desktop);
				} catch (Throwable ex) {
					log.error("Failed to invoke "+klass, ex);
				}
			}
		}
	}

	/** Invokes {@link ExecutionInit#init} for each relevant
	 * listener registered by {@link #addListener}.
	 *
 	 * <p>Used only internally.
	 *
	 * <p>An instance of {@link ExecutionInit} is constructed first,
	 * and then invoke {@link ExecutionInit#init}.
	 *
	 * @param exec the execution that is created
	 * @param parent the previous execution, or null if no previous at all
	 * @exception UiException to prevent an execution from being created
	 */
	public void invokeExecutionInits(Execution exec, Execution parent)
	throws UiException {
		if (_execInits.isEmpty()) return;
			//it is OK to test LinkedList.isEmpty without synchronized

		synchronized (_execInits) {
			for (Iterator it = _execInits.iterator(); it.hasNext();) {
				final Class klass = (Class)it.next();
				try {
					((ExecutionInit)klass.newInstance()).init(exec, parent);
				} catch (Throwable ex) {
					throw UiException.Aide.wrap(ex);
					//Don't intercept; to prevent the creation of a session
				}
			}
		}
	}
	/** Invokes {@link ExecutionCleanup#cleanup} for each relevant
	 * listener registered by {@link #addListener}.
	 *
 	 * <p>Used only internally.
	 *
	 * <p>An instance of {@link ExecutionCleanup} is constructed first,
	 * and then invoke {@link ExecutionCleanup#cleanup}.
	 *
	 * <p>It never throws an exception but logs and adds it to the errs argument,
	 * if not null.
	 *
	 * @param exec the execution that is being destroyed
	 * @param parent the previous execution, or null if no previous at all
	 * @param errs a list of exceptions (java.lang.Throwable) if any exception
	 * occured before this method is called, or null if no exeption at all.
	 * Note: you can manipulate the list directly to add or clean up exceptions.
	 * For example, if exceptions are fixed correctly, you can call errs.clear()
	 * such that no error message will be displayed at the client.
	 */
	public void invokeExecutionCleanups(Execution exec, Execution parent, List errs) {
		if (_execCleans.isEmpty()) return;
			//it is OK to test LinkedList.isEmpty without synchronized

		synchronized (_execCleans) {
			for (Iterator it = _execCleans.iterator(); it.hasNext();) {
				final Class klass = (Class)it.next();
				try {
					((ExecutionCleanup)klass.newInstance())
						.cleanup(exec, parent, errs);
				} catch (Throwable ex) {
					log.error("Failed to invoke "+klass, ex);
					if (errs != null) errs.add(ex);
				}
			}
		}
	}

	/** Invokes {@link URIInterceptor#request} for each relevant listner
	 * registered by {@link #addListener}.
	 *
 	 * <p>Used only internally.
	 *
	 * <p>If any of them throws an exception, the exception is propogated to
	 * the caller.
	 *
	 * @exception UiException if it is rejected by the interceptor.
	 * Use {@link UiException#getCause} to retrieve the cause.
	 */
	public void invokeURIInterceptors(String uri) {
		if (_uriIntcps.isEmpty()) return;

		synchronized (_uriIntcps) {
			for (Iterator it = _uriIntcps.iterator(); it.hasNext();) {
				try {
					((URIInterceptor)it.next()).request(uri);
				} catch (Exception ex) {
					throw UiException.Aide.wrap(ex);
				}
			}
		}
	}
	/** Invokes {@link RequestInterceptor#request} for each relevant listner
	 * registered by {@link #addListener}.
	 *
 	 * <p>Used only internally.
	 *
	 * <p>If any of them throws an exception, the exception is propogated to
	 * the caller.
	 *
	 * @exception UiException if it is rejected by the interceptor.
	 * Use {@link UiException#getCause} to retrieve the cause.
	 */
	public void invokeRequestInterceptors(Session sess, Object request,
	Object response) {
		if (_reqIntcps.isEmpty()) return;

		synchronized (_reqIntcps) {
			for (Iterator it = _reqIntcps.iterator(); it.hasNext();) {
				try {
					((RequestInterceptor)it.next())
						.request(sess, request, response);
				} catch (Exception ex) {
					throw UiException.Aide.wrap(ex);
				}
			}
		}
	}

	/** Adds an CSS resource that will be generated for each ZUML desktop.
	 *
	 * <p>Note: if {@link ThemeProvider} is specified ({@link #setThemeProvider}),
	 * the final theme URIs generated depend on {@link ThemeProvider#getThemeURIs}.
	 */
	public void addThemeURI(String uri) {
		if (uri == null || uri.length() == 0)
			throw new IllegalArgumentException("empty");
		synchronized (_themeURIs) {
			_themeURIs.add(uri);
			_roThemeURIs =
				(String[])_themeURIs.toArray(new String[_themeURIs.size()]);
		}
	}
	/** Returns a readonly list of the URI of the CSS resources that will be
	 * generated for each ZUML desktop (never null).
	 *
	 * <p>Default: an array with zero length.
	 */
	public String[] getThemeURIs() {
		return _roThemeURIs;
	}
	/** Enables or disables the default theme of the specified language.
	 *
	 * <p>Note: if {@link ThemeProvider} is specified ({@link #setThemeProvider}),
	 * the final theme URIs generated depend on {@link ThemeProvider#getThemeURIs}.
	 *
	 * @param lang the language name, such as xul/html and xhtml.
	 * @param enable whether to enable or disable.
	 * If false, the default theme of the specified language is disabled.
	 * Default: enabled.
	 * @since 3.0.0
	 */
	public void addDisabledThemeURI(String uri) {
		if (uri == null || uri.length() == 0)
			throw new IllegalArgumentException();

		synchronized (this) {
			if (_disThemeURIs == null)
				_disThemeURIs = Collections.synchronizedSet(new HashSet(4));
		}
		_disThemeURIs.add(uri);
	}
	/** Returns a set of the theme URIs that are disabled (never null).
	 *
	 * @since 3.0.0
	 * @see #addDisabledThemeURI
	 */
	public Set getDisabledThemeURIs() {
		return _disThemeURIs != null ? _disThemeURIs: Collections.EMPTY_SET;
	}

	/** Returns the theme provider for the current execution,
	 * or null if not available.
	 *
	 * <p>Default: null.
	 *
	 * <p>Note: if specified, the final theme URIs is decided by
	 * the provider. The URIs specified in {@link #getThemeURIs} are
	 * passed to provider, and it has no effect if the provider decides
	 * to ignore them.
	 * @since 3.0.0
	 * @see #getThemeURIs
	 * @see #isDefaultThemeEnabled
	 */
	public ThemeProvider getThemeProvider() {
		return _themeProvider;
	}
	/** Sets the theme provider for the current execution,
	 * or null if not available.
	 *
	 * @param provider the theme provide. If null, the default theme URIs
	 * will be used.
	 * @see #getThemeProvider
	 * @since 3.0.0
	 */
	public void setThemeProvider(ThemeProvider provider) {
		_themeProvider = provider;
	}

	/**
	 * @deprecated As of release 2.4.0, replaced by {@link Devices#setTimeoutURI}.
	 */
	public void setTimeoutURI(String uri) {
		Devices.setTimeoutURI("ajax", uri);
	}
	/**
	 * @deprecated As of release 2.4.0, replaced by {@link Devices#getTimeoutURI}.
	 */
	public String getTimeoutURI() {
		return Devices.getTimeoutURI("ajax");
	}

	/** Sets the class that implements {@link UiEngine}, or null to
	 * use the default.
	 */
	public void setUiEngineClass(Class cls) {
		if (cls != null && !UiEngine.class.isAssignableFrom(cls))
			throw new IllegalArgumentException("UiEngine not implemented: "+cls);
		_uiengcls = cls;
	}
	/** Returns the class that implements {@link UiEngine}, or null if default is used.
	 */
	public Class getUiEngineClass() {
		return _uiengcls;
	}

	/** Sets the class that implements {@link WebApp} and
	 * {@link WebAppCtrl}, or null to use the default.
	 *
	 * <p>Note: you have to set the class before {@link WebApp} is created.
	 * Otherwise, it won't have any effect.
	 */
	public void setWebAppClass(Class cls) {
		if (cls != null && (!WebApp.class.isAssignableFrom(cls)
		|| !WebAppCtrl.class.isAssignableFrom(cls)))
			throw new IllegalArgumentException("WebApp or WebAppCtrl not implemented: "+cls);
		_wappcls = cls;
	}
	/** Returns the class that implements {@link WebApp} and
	 * {@link WebAppCtrl}, or null if default is used.
	 */
	public Class getWebAppClass() {
		return _wappcls;
	}

	/** Sets the class that implements {@link DesktopCacheProvider}, or null to
	 * use the default.
	 *
	 * <p>Note: you have to set the class before {@link WebApp} is created.
	 * Otherwise, it won't have any effect.
	 */
	public void setDesktopCacheProviderClass(Class cls) {
		if (cls != null && !DesktopCacheProvider.class.isAssignableFrom(cls))
			throw new IllegalArgumentException("DesktopCacheProvider not implemented: "+cls);
		_dcpcls = cls;
	}
	/** Returns the class that implements the UI engine, or null if default is used.
	 */
	public Class getDesktopCacheProviderClass() {
		return _dcpcls;
	}

	/** Sets the class that implements {@link UiFactory}, or null to
	 * use the default.
	 *
	 * <p>Note: you have to set the class before {@link WebApp} is created.
	 * Otherwise, it won't have any effect.
	 */
	public void setUiFactoryClass(Class cls) {
		if (cls != null && !UiFactory.class.isAssignableFrom(cls))
			throw new IllegalArgumentException("UiFactory not implemented: "+cls);
		_uiftycls = cls;
	}
	/** Returns the class that implements the UI engine, or null if default is used.
	 */
	public Class getUiFactoryClass() {
		return _uiftycls;
	}

	/** Sets the class that implements {@link FailoverManager}, or null if
	 * no custom failover mechanism.
	 *
	 * <p>Note: you have to set the class before {@link WebApp} is created.
	 * Otherwise, it won't have any effect.
	 */
	public void setFailoverManagerClass(Class cls) {
		if (cls != null && !FailoverManager.class.isAssignableFrom(cls))
			throw new IllegalArgumentException("FailoverManager not implemented: "+cls);
		_failmancls = cls;
	}
	/** Returns the class that implements the failover manger,
	 * or null if no custom failover mechanism.
	 */
	public Class getFailoverManagerClass() {
		return _failmancls;
	}

	/** Sets the class that implements {@link IdGenerator}, or null to
	 * use the default.
	 *
	 * <p>Note: you have to set the class before {@link WebApp} is created.
	 * Otherwise, it won't have any effect.
	 * @since 2.4.1
	 */
	public void setIdGeneratorClass(Class cls) {
		if (cls != null && !IdGenerator.class.isAssignableFrom(cls))
			throw new IllegalArgumentException("IdGenerator not implemented: "+cls);
		_idgencls = cls;
	}
	/** Returns the class that implements {@link IdGenerator},
	 * or null if default is used.
	 * @since 2.4.1
	 */
	public Class getIdGeneratorClass() {
		return _idgencls;
	}

	/** Specifies the maximal allowed time to process events, in miliseconds.
	 * ZK will keep processing the requests sent from
	 * the client until all requests are processed, or the maximal allowed
	 * time expires.
	 *
	 * <p>Default: 3000.
	 *
	 * @param time the maximal allowed time to process events.
	 * It must be positive.
	 */
	public void setMaxProcessTime(int time) {
		_maxProcTime = time;
	}
	/** Returns the maximal allowed time to process events, in miliseconds.
	 * It is always positive
	 */
	public int getMaxProcessTime() {
		return _maxProcTime;
	}

	/** Specifies the maximal allowed upload size, in kilobytes.
	 *
	 * <p>Default: 5120.
	 *
	 * @param sz the maximal allowed upload size.
	 * A negative value indicates therre is no limit.
	 */
	public void setMaxUploadSize(int sz) {
		_maxUploadSize = sz;
	}
	/** Returns the maximal allowed upload size, in kilobytes, or 
	 * a negative value if no limit.
	 */
	public int getMaxUploadSize() {
		return _maxUploadSize;
	}
	/** Returns the charset used to encode the uploaded text file
	 * (never null).
	 *
	 * <p>Default: UTF-8.
	 * @see #getUploadCharsetFinder
	 */
	public String getUploadCharset() {
		return _charsetUpload;
	}
	/** Sets the charset used to encode the upload text file.
	 *
	 * <p>Note: {@link #setUploadCharsetFinder} has the higher priority.
	 *
	 * @param charset the charset to use.
	 * If null or empty, UTF-8 is assumed.
	 * @see #setUploadCharsetFinder
	 */
	public void setUploadCharset(String charset) {
		_charsetUpload = charset != null && charset.length() > 0 ? charset: "UTF-8";
	}
	/** Returns the finder that is used to decide the character set
	 * for the uploaded text filie(s), or null if not available.
	 *
	 * <p>Default: null
	 * @since 3.0.0
	 * @see #getUploadCharset
	 */
	public CharsetFinder getUploadCharsetFinder() {
		return _charsetFinderUpload;
	}
	/** Sets the finder that is used to decide the character set
	 * for the uploaded text filie(s), or null if not available.
	 *
	 * <p>It has the higher priority than {@link #setUploadCharset}.
	 * In other words, {@link #getUploadCharset} is used only if
	 * this method returns null or {@link CharsetFinder#getCharset}
	 * returns null.
	 *
	 * @since 3.0.0
	 * @see #setUploadCharset
	 */
	public void setUploadCharsetFinder(CharsetFinder finder) {
		_charsetFinderUpload = finder;
	}

	/** Specifies the time, in seconds, between client requests
	 * before ZK will invalidate the desktop.
	 *
	 * <p>Default: 3600 (1 hour).
	 *
	 * <p>A negative value indicates the desktop should never timeout.
	 */
	public void setDesktopMaxInactiveInterval(int secs) {
		_dtTimeout = secs;
	}
	/** Returns the time, in seconds, between client requests
	 * before ZK will invalidate the desktop.
	 *
	 * <p>A negative value indicates the desktop should never timeout.
	 */
	public int getDesktopMaxInactiveInterval() {
		return _dtTimeout;
	}

	/** Specifies the time, in milliseconds, before ZK Client Engine shows
	 * a dialog to prompt users that the request is in processming.
	 *
	 * <p>Default: 900
	 */
	public void setProcessingPromptDelay(int minisecs) {
		_promptDelay = minisecs;
	}
	/** Returns the time, in milliseconds, before ZK Client Engine shows
	 * a dialog to prompt users that the request is in processming.
	 */
	public int getProcessingPromptDelay() {
		return _promptDelay;
	}
	/** Specifies the time, in milliseconds, before ZK Client Engine shows
	 * the tooltip when a user moves the mouse over particual UI components.
	 *
	 * <p>Default: 800
	 */
	public void setTooltipDelay(int minisecs) {
		_tooltipDelay = minisecs;
	}
	/** Returns the time, in milliseconds, before ZK Client Engine shows
	 * the tooltip when a user moves the mouse over particual UI components.
	 */
	public int getTooltipDelay() {
		return _tooltipDelay;
	}
	/** Adds the URI to redirect to, when ZK Client Engine receives
	 * an error.
	 *
	 * @param errCode the error code.
	 * @param uri the URI to redirect to. It cannot be null.
	 * If empty, the client will reload the same page again.
	 * If null, it is the same as {@link #removeClientErrorReload}
	 * @return the previous URI associated with the specified error code
	 * @since 3.0.0
	 */
	public String addClientErrorReload(int errCode, String uri) {
		if (uri == null)
			return removeClientErrorReload(errCode);
		return (String)_errURIs.put(new Integer(errCode), uri);
	}
	/** Removes the URI to redirect to, when ZK Client Engine receives
	 * an error.
	 *
	 * @param errCode the error code.
	 * @return the previous URI associated with the specified error code
	 * @since 3.0.0
	 */
	public String removeClientErrorReload(int errCode) {
		return (String)_errURIs.remove(new Integer(errCode));
	}
	/** Returns the URI that is associated with the specified error code,
	 * or null if no URI is associated.
	 * @since 3.0.0
	 */
	public String getClientErrorReload(int errCode) {
		return (String)_errURIs.get(new Integer(errCode));
	}
	/** Returns a readonly array of all error codes that are associated
	 * with URI to redirect to.
	 *
	 * <p>Default: 302, 401 and 403 are associated with an empty URI.
	 * @since 3.0.0
	 */
	public int[] getClientErrorReloadCodes() {
		final Set ks = _errURIs.keySet();
		final int[] cers = new int[ks.size()];
		int j = 0;
		for (Iterator it = ks.iterator(); j < cers.length && it.hasNext();) {
			cers[j++] = ((Integer)it.next()).intValue();
		}
		return cers;
	}

	/**  Specifies the time, in seconds, between client requests
	 * before ZK will invalidate the session.
	 *
	 * <p>Default: 0 (means the system default).
	 *
	 * @see #setTimerKeepAlive
	 * @see Session#setMaxInactiveInterval
	 */
	public void setSessionMaxInactiveInterval(int secs) {
		_sessTimeout = secs;
	}
	/** Returns the time, in seconds, between client requests
	 * before ZK will invalidate the session.
	 *
	 * <p>Default: 0 (means the system default).
	 *
	 * <p>A negative value indicates that there is no limit.
	 * Zero means to use the system default (usually defined in web.xml).
	 *
	 * @see #isTimerKeepAlive
	 * @see Session#getMaxInactiveInterval
	 */
	public int getSessionMaxInactiveInterval() {
		return _sessTimeout;
	}

	/** Specifies the maximal allowed number of desktop
	 * per session.
	 *
	 * <p>Defafult: 10.
	 *
	 * <p>A negative value indicates there is no limit.
	 */
	public void setMaxDesktops(int max) {
		_dtMax = max;
	}
	/** Returns the maximal allowed number of desktop per session.
	 *
	 * <p>A negative value indicates there is no limit.
	 */
	public int getMaxDesktops() {
		return _dtMax;
	}

	/** Specifies the maximal allowed number of the spare pool for
	 * queuing the event processing threads (per Web application).
	 *
	 * <p>Default: 100.
	 *
	 * <p>A negative value indicates there is no limit.
	 *
	 * <p>ZK uses a thread pool to keep the idle event processing threads.
	 * It speeds up the service of an event by reusing the thread queued
	 * in this pool.
	 *
	 * @see #setMaxSuspendedThreads
	 * @see #isEventThreadEnabled
	 */
	public void setMaxSpareThreads(int max) {
		_sparThdMax = max;
	}
	/** Returns the maximal allowed number of the spare pool for
	 * queuing event processing threads (per Web application).
	 * @see #isEventThreadEnabled
	 */
	public int getMaxSpareThreads() {
		return _sparThdMax;
	}

	/** Specifies the maximal allowed number of suspended event
	 * processing threads (per Web application).
	 *
	 * <p>Default: -1 (no limit).
	 *
	 * <p>A negative value indicates there is no limit.
	 *
	 * <p>It is ignored if the use of the event processing thread
	 * is disable ({@link #isEventThreadEnabled}.
	 */
	public void setMaxSuspendedThreads(int max) {
		_suspThdMax = max;
	}
	/** Returns the maximal allowed number of suspended event
	 * processing threads (per Web application).
	 *
	 * <p>It is ignored if the use of the event processing thread
	 * is disable ({@link #isEventThreadEnabled}.
	 * @see #isEventThreadEnabled
	 */
	public int getMaxSuspendedThreads() {
		return _suspThdMax;
	}
	/** Sets whether to use the event processing thread.
	 *
	 * <p>Default: enabled.
	 *
	 * @exception IllegalStateException if there is suspended thread
	 * and use is false.
	 */
	public void enableEventThread(boolean enable) {
		if (!enable && _wapp != null) {
			final UiEngine engine = ((WebAppCtrl)_wapp).getUiEngine();
			if (engine != null) {
				if (engine.hasSuspendedThread())
					throw new IllegalStateException("Unable to disable due to suspended threads");
			}
		}
		_useEvtThd = enable;
	}
	/** Returns whether to use the event processing thread.
	 */
	public boolean isEventThreadEnabled() {
		return _useEvtThd;
	}

	/** Returns whether to disable the components that don't belong to
	 * the active modal window.
	 *
	 * <p>Default: true.
	 * @since 2.4.1
	 */
	public boolean isDisableBehindModalEnabled() {
		return _disableBehindModal;
	}
	/** Sets whether to disable the components that don't belong to
	 * the active modal window.
	 *
	 * <p>Default: true.
	 * @since 2.4.1
	 */
	public void enableDisableBehindModal(boolean enable) {
		_disableBehindModal = enable;
	}

	/** Returns the monitor for this application, or null if not set.
	 */
	public Monitor getMonitor() {
		return _monitor;
	}
	/** Sets the monitor for this application, or null to disable it.
	 *
	 * <p>Default: null.
	 *
	 * <p>There is at most one monitor for each Web application.
	 * The previous monitor will be replaced when this method is called.
	 *
	 * <p>In addition to call this method, you could specify a monitor
	 * in zk.xml
	 *
	 * @param monitor the performance meter. If null, the meter function
	 * is disabled.
	 * @return the previous monitor, or null if not available.
	 */
	public Monitor setMonitor(Monitor monitor) {
		final Monitor old = _monitor;
		_monitor = monitor;
		return old;
	}

	/** Returns the performance meter for this application, or null if not set.
	 * @since 3.0.0
	 */
	public PerformanceMeter getPerformanceMeter() {
		return _pfmeter;
	}
	/** Sets the performance meter for this application, or null to disable it.
	 *
	 * <p>Default: null.
	 *
	 * <p>There is at most one performance meter for each Web application.
	 * The previous meter will be replaced when this method is called.
	 *
	 * <p>In addition to call this method, you could specify
	 * a performance meter in zk.xml
	 *
	 * @param meter the performance meter. If null, the meter function
	 * is disabled.
	 * @return the previous performance meter, or null if not available.
	 * @since 3.0.0
	 */
	public PerformanceMeter setPerformanceMeter(PerformanceMeter meter) {
		final PerformanceMeter old = _pfmeter;
		_pfmeter = meter;
		return old;
	}

	/** Returns the charset used to generate the HTTP response
	 * or null to use the container's default.
	 * It is currently used by {@link org.zkoss.zk.ui.http.DHtmlLayoutServlet},
	 *
	 * <p>Default: UTF-8.
	 */
	public String getResponseCharset() {
		return _charsetResp;
	}
	/** Sets the charset used to generate HTTP response.
	 * It is currently used by {@link org.zkoss.zk.ui.http.DHtmlLayoutServlet},
	 *
	 * @param charset the charset to use. If null or empty, the container's default
	 * is used.
	 */
	public void setResponseCharset(String charset) {
		_charsetResp = charset != null && charset.length() > 0 ? charset: null;
	}

	/** Returns the value of the preference defined in zk.xml, or by
	 * {@link #setPreference}.
	 *
	 * <p>Preference is application specific. You can specify whatever you want
	 * as you specifying context-param for a Web application.
	 *
	 * @param defaultValue the default value that is used if the specified
	 * preference is not found.
	 */
	public String getPreference(String name, String defaultValue) {
		final String value = (String)_prefs.get(name);
		return value != null ? value: defaultValue;
	}
	/** Sets the value of the preference.
	 */
	public void setPreference(String name, String value) {
		if (name == null || value == null)
			throw new IllegalArgumentException("null");
		_prefs.put(name, value);
	}
	/** Returns a readonly set of all preference names.
	 */
	public Set getPreferenceNames() {
		return _prefs.keySet();
	}

	/** Adds the definition of a richlet.
	 *
	 * @param name the richlet name
	 * @param params the initial parameters, or null if no initial parameter at all.
	 * Once called, the caller cannot access <code>params</code> any more.
	 * @return the previous richlet class or class-name with the specified name,
	 * or null if no previous richlet.
	 */
	public Object addRichlet(String name, Class richletClass, Map params) {
		if (!Richlet.class.isAssignableFrom(richletClass))
			throw new IllegalArgumentException("A richlet class, "+richletClass+", must implement "+Richlet.class.getName());

		return addRichlet0(name, richletClass, params);
	}
	/** Adds the definition of a richlet.
	 *
	 * @param name the richlet name
	 * @param richletClassName the class name. The class will be loaded
	 * only when the richlet is loaded.
	 * @param params the initial parameters, or null if no initial parameter at all.
	 * Once called, the caller cannot access <code>params</code> any more.
	 * @return the previous richlet class or class-name with the specified name,
	 * or null if no previous richlet.
	 */
	public Object addRichlet(String name, String richletClassName, Map params) {
		if (richletClassName == null || richletClassName.length() == 0)
			throw new IllegalArgumentException("richletClassName is required");

		return addRichlet0(name, richletClassName, params);
	}
	private Object addRichlet0(String name, Object richletClass, Map params) {
		final Object o;
		synchronized (_richlets) {
			o = _richlets.put(name, new Object[] {richletClass, params});
		}

		if (o == null)
			return null;
		if (o instanceof Richlet) {
			destroy((Richlet)o);
			return o.getClass();
		}
		return ((Object[])o)[0];
	}
	/** Adds a richlet mapping.
	 *
	 * @param name the name of the richlet.
	 * @param path the URL pattern. It must start with '/' and may end
	 * with '/*'.
	 * @exception UiException if the richlet is not defined yet.
	 * See {@link #addRichlet}.
	 * @since 2.4.0
	 */
	public void addRichletMapping(String name, String path) {
		//first, check whether the richlet is defined
		synchronized (_richlets) {
			if (!_richlets.containsKey(name))
				throw new UiException("Richlet not defined: "+name);
		}

		//richletClass was checked before calling this method
		//Note: "/" is the same as ""
		if (path == null || path.length() == 0 || "/".equals(path))
			path = "";
		else if (!path.startsWith("/"))
			throw new IllegalArgumentException("path must start with '/', not "+path);

		final boolean wildcard = path.endsWith("/*");
		if (wildcard) //wildcard
			path = path.substring(0, path.length() - 2);
				//note it might be empty

		synchronized (_richletmaps) {
			_richletmaps.put(
				path, new Object[] {name, Boolean.valueOf(wildcard)});
		}
	}
	private static void destroy(Richlet richlet) {
		try {
			richlet.destroy();
		} catch (Throwable ex) {
			log.error("Unable to destroy "+richlet);
		}
	}
	/** Returns an instance of richlet of the specified name, or null
	 * if not found.
	 */
	public Richlet getRichlet(String name) {
		WaitLock lock = null;
		final Object[] info;
		for (;;) {
			synchronized (_richlets) {
				Object o = _richlets.get(name);
				if (o == null || (o instanceof Richlet)) { //not found or loaded
					return (Richlet)o;
				} else if (o instanceof WaitLock) { //loading by another thread
					lock = (WaitLock)o;
				} else {
					info = (Object[])o;

					//going to load in this thread
					_richlets.put(name, lock = new WaitLock());
					break; //then, load it
				}
			} //sync(_richlets)

			if (!lock.waitUntilUnlock(300*1000)) { //5 minute
				final PotentialDeadLockException ex =
					new PotentialDeadLockException(
					"Unable to load richlet "+name+"\nCause: conflict too long.");
				log.warningBriefly(ex); //very rare, possibly a bug
				throw ex;
			}
		} //for (;;)

		//load it
		try {
			if (info[0] instanceof String) {
				try {
					info[0] = Classes.forNameByThread((String)info[0]);
				} catch (Throwable ex) {
					throw new UiException("Failed to load "+info[0]);
				}
			}

			final Object o = ((Class)info[0]).newInstance();
			if (!(o instanceof Richlet))
				throw new UiException(Richlet.class+" must be implemented by "+info[0]);

			final Richlet richlet = (Richlet)o;
			richlet.init(new RichletConfigImpl(_wapp, (Map)info[1]));

			synchronized (_richlets) {
				_richlets.put(name, richlet);
			}
			return richlet;
		} catch (Throwable ex) {
			synchronized (_richlets) {
				_richlets.put(name, info); //remove lock and restore info
			}
			throw UiException.Aide.wrap(ex, "Unable to instantiate "+info[0]);
		} finally {
			lock.unlock();
		}
	}
	/** Returns an instance of richlet for the specified path, or
	 * null if not found.
	 */
	public Richlet getRichletByPath(String path) {
		if (path == null || path.length() == 0 || "/".equals(path))
			path = "";
		else if (path.charAt(0) != '/')
			path = '/' + path;

		final int len = path.length();
		for (int j = len;;) {
			final Richlet richlet =
				getRichletByPath0(path.substring(0, j), j != len);
			if (richlet != null || j == 0)
				return richlet;
			j = path.lastIndexOf('/', j - 1); //j must not -1
		}
	}
	private Richlet getRichletByPath0(String path, boolean wildcardOnly) {
		final Object[] info;
		synchronized (_richletmaps) {
			info = (Object[])_richletmaps.get(path);
		}
		return info != null &&
			(!wildcardOnly || ((Boolean)info[1]).booleanValue()) ?
				getRichlet((String)info[0]): null;
	}
	/** Destroyes all richlets.
	 */
	public void detroyRichlets() {
		synchronized (_richlets) {
			for (Iterator it = _richlets.values().iterator(); it.hasNext();) {
				final Object o = it.next();
				if (o instanceof Richlet)
					destroy((Richlet)o);
			}
			_richlets.clear();
		}
	}

	/** Specifies whether to keep the desktops across visits.
	 * If false, the desktops are removed when an user reloads an URL
	 * or browses to another URL.
	 *
	 * <p>Default: false.
	 */
	public void setKeepDesktopAcrossVisits(boolean keep) {
		_keepDesktop = keep;
	}
	/** Returns whether to keep the desktops across visits.
	 * If false, the desktops are removed when an user reloads an URL
	 * or browses to another URL.
	 */
	public boolean isKeepDesktopAcrossVisits() {
		return _keepDesktop;
	}

	/** Specifies whether to keep the session alive,
	 * when receiving the onTimer event.
	 *
	 * <p>Default: false.
	 *
	 * <p>A session is expired (and then invalidated), if it didn't receive
	 * any client request in the specified timeout interval
	 * ({@link #getSessionMaxInactiveInterval}).
	 * By setting this option to true, the session timeout will be reset
	 * when onTimer is received (just like any other event).
	 *
	 * <p>Note: if true and the timer is shorter than
	 * the session timeout ({@link #getSessionMaxInactiveInterval}),
	 * the session is never expired.
	 *
	 * @param alive whether to keep the session alive when receiving
	 * onTimer
	 * @since 3.0.0
	 */
	public void setTimerKeepAlive(boolean alive) {
		_timerKeepAlive = alive;
	}
	/** Returns whether to keep the session alive,
	 * when receiving the onTimer event.
	 * In other words, it returns whether to reset the session timeout
	 * counter when receiving onTimer, just like any other events.
	 *
	 * @since 3.0.0
	 */
	public boolean isTimerKeepAlive() {
		return _timerKeepAlive;
	}

	/** Sets the implementation of the expression factory that shall
	 * be used by the whole system.
	 *
	 * <p>Default: null -- it means the org.zkoss.xel.el.ELFactory class
	 * (it requires zcommons-el.jar).
	 *
	 * <p>Note: you can only specify an implementation that is compatible
	 * with JSP EL here, since ZK's builtin pages depend on it.
	 * However, you can use any factory you like in an individual page,
	 * as long as all expressions in the page follow the syntax of
	 * the evaluator you are using.
	 *
	 * @param expfcls the implemtation class, or null to use the default.
	 * Note: expfcls must implement {@link ExpressionFactory}.
	 * @since 3.0.0
	 */
	public void setExpressionFactoryClass(Class expfcls) {
		Expressions.setExpressionFactoryClass(expfcls);
	}
	/** Returns the implementation of the expression factory that
	 * is used by the whole system, or null if the sytem default is used.
	 *
	 * @see #setExpressionFactoryClass
	 * @since 3.0.0
	 */
	public Class getExpressionFactoryClass() {
		return Expressions.getExpressionFactoryClass();
	}

	/** Invokes {@link EventInterceptor#beforeSendEvent}
	 * registered by {@link #addListener} with a class implementing
	 * {@link EventInterceptor}.
	 * <p>Used only internally.
	 * @since 3.0.0
	 */
	public Event beforeSendEvent(Event event) {
		return _eis.beforeSendEvent(event);
	}
	/** Invokes {@link EventInterceptor#beforePostEvent}
	 * registered by {@link #addListener} with a class implementing
	 * {@link EventInterceptor}.
	 * <p>Used only internally.
	 * @since 3.0.0
	 */
	public Event beforePostEvent(Event event) {
		return _eis.beforePostEvent(event);
	}
	/** Invokes {@link EventInterceptor#beforeProcessEvent}
	 * registered by {@link #addListener} with a class implementing
	 * {@link EventInterceptor}.
	 * <p>Used only internally.
	 * @since 3.0.0
	 */
	public Event beforeProcessEvent(Event event) {
		return _eis.beforeProcessEvent(event);
	}
	/** Invokes {@link EventInterceptor#afterProcessEvent}
	 * registered by {@link #addListener} with a class implementing
	 * {@link EventInterceptor}.
	 * <p>Used only internally.
	 * @since 3.0.0
	 */
	public void afterProcessEvent(Event event) {
		_eis.afterProcessEvent(event);
	}

	/**
	 * @deprecated As of release 2.4.1, replaced by {@link #addErrorPage(String, Class, String)}
	 */
	public void addErrorPage(Class type, String location) {
		addErrorPage("ajax", type, location);
	}
	/** Adds an error page.
	 *
	 * @param deviceType the device type: ajax or mil
	 * @param type what type of errors the error page is associated with.
	 * @param location where is the error page.
	 * @return the previous location of the same error, or null if not
	 * defined yet.
	 * @since 2.4.1
	 */
	public String addErrorPage(String deviceType, Class type, String location) {
		if (!Throwable.class.isAssignableFrom(type))
			throw new IllegalArgumentException("Throwable or derived is required: "+type);
		if (location == null || deviceType == null)
			throw new IllegalArgumentException();

		List l;
		synchronized (_errpgs) {
			l = (List)_errpgs.get(deviceType);
			if (l == null)
				_errpgs.put(deviceType, l = new LinkedList());
		}

		String previous = null;
		synchronized (l) {
			//remove the previous definition
			for (Iterator it = l.iterator(); it.hasNext();) {
				final ErrorPage errpg = (ErrorPage)it.next();
				if (errpg.type.equals(type)) {
					previous = errpg.location;
					it.remove();
					break;
				}
			}
			l.add(new ErrorPage(type, location));
		}
		return previous;
	}
	/**
	 * @deprecated As of release 2.4.1, replaced by {@link #getErrorPage(String, Throwable)}.
	 */
	public String getErrorPage(Throwable error) {
		return getErrorPage("ajax", error);
	}
	/** Returns the error page that matches the specified error, or null if not found.
	 *
	 * @param deviceType the device type: ajax or mil
	 * @param error the exception being thrown
	 * @since 2.4.1
	 */
	public String getErrorPage(String deviceType, Throwable error) {
		if (!_errpgs.isEmpty()) {
			final List l;
			synchronized (_errpgs) {
				l = (List)_errpgs.get(deviceType);
			}
			if (l != null) {
				synchronized (l) {
					for (Iterator it = l.iterator(); it.hasNext();) {
						final ErrorPage errpg = (ErrorPage)it.next();
						if (errpg.type.isInstance(error))
							return errpg.location;
					}
				}
			}
		}
		return null;
	}
	private static class ErrorPage {
		private final Class type;
		private final String location;
		private ErrorPage(Class type, String location) {
			this.type = type;
			this.location = location;
		}
	};
}
