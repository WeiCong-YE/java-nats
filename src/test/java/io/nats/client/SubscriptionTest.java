/*******************************************************************************
 * Copyright (c) 2015-2016 Apcera Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the MIT License (MIT)
 * which accompanies this distribution, and is available at
 * http://opensource.org/licenses/MIT
 *******************************************************************************/
package io.nats.client;

import static org.mockito.Mockito.*;
import static org.junit.Assert.*;
import static io.nats.client.UnitTestUtilities.*;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.nats.client.Constants.*;

@Category(UnitTest.class)
public class SubscriptionTest {
	final Logger logger = LoggerFactory.getLogger(SubscriptionTest.class);

	ExecutorService executor = Executors.newCachedThreadPool(new NATSThreadFactory("nats-test-thread"));

	@Rule
	public TestCasePrinterRule pr = new TestCasePrinterRule(System.out);

	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
	}

	@AfterClass
	public static void tearDownAfterClass() throws Exception {
	}

	@Before
	public void setUp() throws Exception {
		UnitTestUtilities.startDefaultServer();
	}

	@After
	public void tearDown() throws Exception {
		UnitTestUtilities.stopDefaultServer();
	}

	@Test
	public void testToString()
	{
		ConnectionImpl nc = null;
		Subscription s = new AsyncSubscriptionImpl(nc, "foo", "bar", null, 20);
		assertEquals("{subject=foo, queue=bar, sid=0, queued=0, max=20, valid=false}", s.toString());
		s = new AsyncSubscriptionImpl(nc, "foo", null, null, -1);
		assertEquals("{subject=foo, queue=null, sid=0, queued=0, max=65536, valid=false}", s.toString());
	}
	
	@Test
	public void testServerAutoUnsub()
	{
		try (Connection c = new ConnectionFactory().createConnection())
		{
			assertFalse(c.isClosed());
			final AtomicLong received = new AtomicLong(0L);
			int max = 10;

			try (Subscription s = c.subscribe("foo", new MessageHandler () {
				@Override
				public void onMessage(Message msg) {
					received.incrementAndGet();
				}
			}))
			{
				s.autoUnsubscribe(max);
				int total = 100;

				for (int i = 0; i < total; i++)
				{
					c.publish("foo", "Hello".getBytes());
				}
				try {
					c.flush();
				} catch (Exception e1) {
					fail(e1.getMessage());
				}

				try {Thread.sleep(100); } catch (InterruptedException e) {}

				assertEquals(max, received.get());

				assertFalse("Expected subscription to be invalid after hitting max",
						s.isValid());
			}
		} catch (IOException | TimeoutException e1) {
			fail("Should have connected: " + e1.getMessage());
		}
	}

	@Test
	public void testClientSyncAutoUnsub()
	{
		try (Connection c = new ConnectionFactory().createConnection()) {
			assertFalse(c.isClosed());

			long received = 0;
			int max = 10;
			boolean exThrown = false;
			try (SyncSubscription s = c.subscribeSync("foo")) {
				s.autoUnsubscribe(max);

				int total = 100;
				for (int i = 0; i < total; i++)
				{
					c.publish("foo", "Hello".getBytes());
				}
				try {
					c.flush();
				} catch (Exception e) {
					fail(e.getMessage());
				}

				while (true)
				{
					try
					{
						s.nextMessage(100);
						received++;
					}
					catch (IOException e) {
						assertEquals(ERR_MAX_MESSAGES, e.getMessage());
						exThrown = true;
						break;
					}
					catch (Exception e) {
						// catch-all
						fail("Wrong exception: " + e.getMessage());
					}
				}
				assertTrue("Should have thrown IOException", exThrown);
				assertEquals(max, received);
				assertFalse("Expected subscription to be invalid after hitting max", 
						s.isValid());
			}
		} catch (IOException | TimeoutException e2) {
			fail("Should have connected");
		}
	}

	@Test
	public void testClientAsyncAutoUnsub()
	{
		final AtomicInteger received = new AtomicInteger(0);
		MessageHandler mh = new MessageHandler()
		{
			@Override
			public void onMessage(Message msg) {
				received.getAndIncrement();
			}
		};

		try (Connection c = new ConnectionFactory().createConnection()) {
			assertFalse(c.isClosed());

			int max = 10;
			try (Subscription s = c.subscribe("foo", mh )) {
				s.autoUnsubscribe(max);

				int total = 100;
				for (int i = 0; i < total; i++)
				{
					c.publish("foo", "Hello".getBytes());
				}
				try { c.flush(); } catch (Exception e1) {}

				try {Thread.sleep(10);} catch (InterruptedException e) {}
				assertFalse("Expected subscription to be invalid after hitting max", 
						s.isValid());
				assertEquals(max, received.get());
			} 
		} catch (IOException | TimeoutException e) {
			fail("Should have connected");
		}
	}

	@Test
	public void testCloseSubRelease()
	{
		try (final Connection c = new ConnectionFactory().createConnection())
		{
			try (SyncSubscription s = c.subscribeSync("foo"))
			{
				long timeout = 50;
				long t0 = System.nanoTime();
				executor.execute(new Runnable() {
					@Override
					public void run() {
						try { Thread.sleep(1); } catch (InterruptedException e) {}
						c.close();
						assertTrue(c.isClosed());
					}
				});
				boolean exThrown=false;
				try {
					try { Thread.sleep(5); } catch (InterruptedException e) {}
					s.nextMessage(timeout);
				} catch (Exception e) {

					exThrown=true;
				} finally {
					assertTrue("Connection should be closed", c.isClosed());
					assertTrue("Expected an error from nextMsg", exThrown);
				}

				long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-t0);
				String msg = String.format("Too much time has elapsed to release NextMsg: %dms", elapsed);
				assertTrue(msg, elapsed <= 50);
			} catch (Exception e) {
				fail("Subscription failed: " + e.getMessage());
			}	
		} catch (IOException | TimeoutException e2) {
			fail("Failed to connect");
		}
	}

	@Test
	public void testIsValidSubscriber() 
	{
		try (final Connection nc = new ConnectionFactory().createConnection())
		{
			try (SyncSubscription sub = nc.subscribeSync("foo")) {
				assertTrue("Subscription should be valid", sub.isValid());

				for (int i = 0; i < 10; i++) {
					nc.publish("foo", "Hello".getBytes());
				}
				nc.flush();

				try {
					sub.nextMessage(200);
				} catch (Exception e) {
					fail("nextMsg threw an exception: " + e.getMessage());
				}

				sub.unsubscribe();


				boolean exThrown = false;
				try { 
					sub.autoUnsubscribe(1);
				} catch (Exception e) {
					assertTrue(e instanceof IllegalStateException);
					assertEquals(ERR_BAD_SUBSCRIPTION, e.getMessage());
					exThrown=true;
				} finally {
					assertTrue("nextMsg should have thrown an exception", 
							exThrown);
				}

				exThrown = false;
				try { 
					sub.nextMessage(200); 
					fail("Shouldn't be here");
				} catch (Exception e) {
					assertTrue(e instanceof IllegalStateException);
					assertEquals(ERR_BAD_SUBSCRIPTION, e.getMessage());
					exThrown=true;
				} finally {
					assertTrue("nextMsg should have thrown an exception", 
							exThrown);
				}
			} catch (Exception e) {
				fail(e.getMessage());
			}
		} catch (IOException | TimeoutException e) {
			fail(e.getMessage());
		}
	}

	@Test
	public void testSlowSubscriber()
	{
		ConnectionFactory cf = new ConnectionFactory();
		cf.setMaxPendingMsgs(100);
		final AtomicBoolean exThrown = new AtomicBoolean(false);

		try (Connection c = cf.createConnection())
		{
			try (SyncSubscription s = c.subscribeSync("foo")) {
				for (int i=0; i < (cf.getMaxPendingMsgs()+100); i++)
				{
					c.publish("foo", "Hello".getBytes());
				}

				int timeout = 5000;
				long t0 = System.nanoTime();
				try {
					c.flush(timeout);
				} catch (Exception e1) {
					/* NOOP */
				}
				long elapsed = TimeUnit.NANOSECONDS.toMillis(
						System.nanoTime()-t0);
				assertFalse(
						String.format(
								"Flush did not return before timeout: %d >= %d",
								elapsed, timeout),
						elapsed >= timeout);
				exThrown.set(false);
				try 
				{
					s.nextMessage(200);
				}
				catch (IOException e)
				{
					assertEquals(ERR_SLOW_CONSUMER, e.getMessage());
					exThrown.set(true);
				} finally {
					assertTrue("nextMsg should have thrown an exception",
							exThrown.get());
				}
			} 
		} catch (IOException | TimeoutException e1) {
			fail("Should have connected: " + e1.getMessage());
		}
	}

	@Test
	public void testSlowAsyncSubscriber() throws IOException, TimeoutException
	{
		ConnectionFactory cf = new ConnectionFactory();
		cf.setMaxPendingMsgs(100);

		try (final Connection c = cf.createConnection()) {
			final Lock mu = new ReentrantLock();
			final Condition released = mu.newCondition();
			try (final AsyncSubscription s = c.subscribeAsync("foo", new MessageHandler() {
				@Override
				public void onMessage(Message msg) {
					mu.lock();
					try
					{
						assertTrue(released.await(5, TimeUnit.SECONDS));
					} catch (InterruptedException e) {
					} finally {
						mu.unlock();
					}
				}
			})) {

				for (int i = 0; i < (cf.getMaxPendingMsgs() + 100); i++)
				{
					c.publish("foo", "Hello".getBytes());
				}

				int flushTimeout = 5000;

				long t0 = System.nanoTime();
				long elapsed = 0L;
				boolean exThrown = false;
				try
				{
					c.flush(flushTimeout);
				}
				catch (Exception e)
				{
					exThrown = true;
				}
				elapsed = TimeUnit.NANOSECONDS.toMillis(
						System.nanoTime() - t0);
				assertTrue("Flush did not return before timeout, elapsed msec="+elapsed, 
						elapsed < flushTimeout);

				mu.lock();
				try
				{
					released.signal();
				} finally {
					mu.unlock();
				}

				assertTrue("Expected an error indicating slow consumer", exThrown);
			}
		}
	}

	@Test
	public void testAsyncErrHandler()
	{
		final String subj = "async_test";
		final AtomicInteger aeCalled = new AtomicInteger(0);

		ConnectionFactory cf = new ConnectionFactory();
		cf.setMaxPendingMsgs(10);

		final Channel<Boolean> ch = new Channel<Boolean>(1);
		final Channel<Boolean> bch = new Channel<Boolean>(1);

		final MessageHandler mcb = new MessageHandler()
		{
			@Override
			public void onMessage(Message msg) {
				if (aeCalled.get()==1)
					return;
				bch.get();
			}
		};

		try (Connection c = cf.createConnection()) {
			try (final AsyncSubscription s = c.subscribeAsync(subj, mcb)) 
			{
				c.setExceptionHandler(new ExceptionHandler()
				{
					public void onException(NATSException e) {

						// Suppress additional calls
						if (aeCalled.get() == 1)
							return;
						aeCalled.incrementAndGet();					

						assertEquals("Did not receive proper subscription", s, e.getSubscription());
						assertTrue("Expected IOException, but got " + e, 
								e.getCause() instanceof IOException);
						assertEquals(ERR_SLOW_CONSUMER, e.getCause().getMessage());

						bch.add(true);

						ch.add(true);

					}
				});

				for (int i = 0; i < (cf.getMaxPendingMsgs() + 10); i++)
				{
					c.publish(subj, "Hello World!".getBytes());
				}
				try {
					c.flush(5000);
				} catch (Exception e) { /* NOOP */ }


				assertTrue("Failed to call async err handler", ch.get(5000));

			} // AsyncSubscription
		} // Connection 
		catch (IOException | TimeoutException e) {
			fail(e.getMessage());
		}
	}

	@Test
	public void testAsyncSubscriberStarvation()
	{
		final Channel<Boolean> ch = new Channel<Boolean>();

		try (final Connection c = new ConnectionFactory().createConnection())
		{
			// Helper
			try (AsyncSubscription helper = c.subscribe("helper",
					new MessageHandler() {
				public void onMessage(Message msg) {
//					System.err.println("Helper");
					UnitTestUtilities.sleep(100);
					c.publish(msg.getReplyTo(), "Hello".getBytes());				
				}
			})) 
			{
//				System.err.println("helper subscribed");
				// Kickoff
				try (AsyncSubscription start = c.subscribe("start", new MessageHandler() {
					public void onMessage(Message msg) {
//						System.err.println("Responder");
						String responseIB = c.newInbox();
						c.subscribe(responseIB, new MessageHandler() {
							public void onMessage(Message msg) {
								// System.err.println("Internal subscriber.");
								UnitTestUtilities.sleep(100);
								ch.add(true);
							}
						}); 
//						System.err.println("starter subscribed");
						UnitTestUtilities.sleep(100);
						c.publish("helper", responseIB, "Help me!".getBytes());
					} // "start" onMessage
				})) 
				{
					UnitTestUtilities.sleep(100);
					c.publish("start", "Begin".getBytes());
//					System.err.println("Started");
					assertTrue("Was stalled inside of callback waiting on another callback",
							ch.get(5000));
				} // Start
			} // Helper
		} catch (IOException | TimeoutException e) {
			fail(e.getMessage());
		}
	}

	@Test
	public void testAsyncSubscribersOnClose()
	{
		// Tests if the subscriber sub channel gets
		// cleared on a close.
		final AtomicInteger callbacks = new AtomicInteger(0);
		int toSend = 10;
		final Channel<Boolean> ch = new Channel<Boolean>(toSend);

		MessageHandler mh = new MessageHandler()
		{
			public void onMessage(Message msg) {
				callbacks.getAndIncrement();
				ch.get();
			}
		};

		try (Connection c = new ConnectionFactory().createConnection()) {
			try (AsyncSubscription s = c.subscribe("foo", mh)) 
			{
				for (int i = 0; i < toSend; i++)
				{
					c.publish("foo", "Hello World!".getBytes());
				}
				try {
					c.flush();
				} catch (Exception e) {
					fail("Flush failure: " + e.getMessage());
				}
				sleep(10);
				
				/*
				 *  Since callbacks for a given sub are invoked sequentially in 
				 *  the same thread, only one message has been dequeued from the
				 *  subscription's message channel and delivered to the MessageHandler 
				 *  callback at this point. The callback is waiting on a boolean signal 
				 *  channel before proceeding.
				 *  
				 *  Closing the connection will remove and close the subsriptions
				 *  on that connection, which will clear our subscription's
				 *  message channel of any queued message. Therefore, no matter how many 
				 *  times we signal ch below, only that one message will be processed. 
				 */

				c.close();

				assertEquals(0, s.getQueuedMessageCount());

				// Release callbacks
				for (int i=0; i<toSend; i++) {
					ch.add(true);
				}

				sleep(10);

				assertEquals(String.format(
						"Expected only one callback, received %d callbacks\n",
						callbacks.get()),
						1, callbacks.get());
			}
		} // Connection
		catch (IOException | TimeoutException e1) {
			fail("Should have connected: " + e1.getMessage());
		}
	}

	@Test 
	public void testManyRequests() throws Exception {
		final int numRequests = 1000;
		ConnectionFactory cf = new ConnectionFactory(Constants.DEFAULT_URL);
		try(final Connection conn = cf.createConnection()) {
			try (final Connection pub = cf.createConnection()) {
				try(Subscription sub = conn.subscribe("foo", "bar", new MessageHandler() {
					public void onMessage(Message message) {
						conn.publish(message.getReplyTo(), "hello".getBytes());
					}
				}))
				{
					for (int i = 0; i < numRequests; i++) {
						try {
							assertNotNull(pub.request("foo", "blah".getBytes(), 5000));
						} catch (TimeoutException e) {
							fail("timeout " + i);
							return;
						}
					} // for
				} // Subscription
			} // pub
		} // conn 
	}
}
