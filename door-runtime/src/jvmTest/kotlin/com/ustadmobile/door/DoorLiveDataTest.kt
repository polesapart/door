package com.ustadmobile.door

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.timeout
import org.mockito.kotlin.verify
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.junit.Assert
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class LiveDataTest {

    @Test
    fun givenEmptyLiveData_whenObservedForever_thenShouldCallOnActive() {
        val countdownLatch = CountDownLatch(1)
        val livedataTest = object : LiveData<Int>() {
            override fun onActive() {
                super.onActive()
                countdownLatch.countDown()
            }
        }
        livedataTest.observeForever(mock {})
        countdownLatch.await(5, TimeUnit.SECONDS)
        Assert.assertEquals("onActive was called when adding a forever observer", 0,
                countdownLatch.count)
    }

    @Test
    fun givenEmptyLiveData_whenObserveCalledAndLifeCycleHasStarted_thenShouldCallOnActive() {
        val mockLifecycleOwner = mock<LifecycleOwner> {
            on {  }.thenReturn(DoorLifecycleObserver.STARTED)
        }

        val countdownLatch = CountDownLatch(1)
        val livedataTest = object : LiveData<Int>() {
            override fun onActive() {
                super.onActive()
                countdownLatch.countDown()
            }
        }

        livedataTest.observe(mockLifecycleOwner, mock {})

        countdownLatch.await(5, TimeUnit.SECONDS)
        Assert.assertEquals("onActive was called when adding a forever observer", 0,
                countdownLatch.count)
    }


    @Test
    fun givenEmptyLiveData_whenObserveCalledAndLifeCycleStarts_thenShouldCallOnActive() {
        val lifecycleState = AtomicInteger(DoorLifecycleObserver.STARTED)
        val mockLifecycleOwner = mock<LifecycleOwner> {
            on { currentState }.thenAnswer { invocation -> lifecycleState.get() }
            on { addObserver(any()) }.thenAnswer {invocation ->
                val listener = invocation.arguments[0] as DoorLifecycleObserver
                GlobalScope.launch {
                    delay(100)
                    listener.onStart(invocation.mock as LifecycleOwner)
                }
                Unit
            }
        }

        val countdownLatch = CountDownLatch(1)
        val livedataTest = object : LiveData<Int>() {
            override fun onActive() {
                super.onActive()
                countdownLatch.countDown()
            }
        }

        livedataTest.observe(mockLifecycleOwner, mock {})

        countdownLatch.await(5, TimeUnit.SECONDS)
        Assert.assertEquals("onActive was called when adding a forever observer", 0,
                countdownLatch.count)
    }

    @Test
    fun givenEmptyLiveData_whenObsevedAndNewValuePosted_thenShouldCallOnChanged() {
        val livedataTest = DoorMutableLiveData<Int>()
        val mockObserver = mock<Observer<Int>> {

        }

        livedataTest.observeForever(mockObserver)
        livedataTest.sendValue(42)
        verify(mockObserver, timeout(5000)).onChanged(42)
    }

    @Test
    fun givenLiveData_whenForeverObserveRemoved_thenShouldCallOnInactive() {
        val lastOnActiveTime = AtomicLong(-1L)
        val lastOnInactiveTime = AtomicLong(-1L)

        val inactiveCountdownLatch = CountDownLatch(1)
        val livedataTest = object : LiveData<Int>() {
            override fun onActive() {
                super.onActive()
                lastOnActiveTime.set(System.currentTimeMillis())
            }

            override fun onInactive() {
                super.onInactive()
                lastOnInactiveTime.set(System.currentTimeMillis())
                inactiveCountdownLatch.countDown()
            }
        }

        val observer = mock<Observer<Int>> {}

        livedataTest.observeForever(observer)
        Thread.sleep(100)
        livedataTest.removeObserver(observer)

        inactiveCountdownLatch.await(5, TimeUnit.SECONDS)
        Assert.assertEquals("OnInactive was called", 0, inactiveCountdownLatch.count)
        Assert.assertNotEquals("OnActive was called", -1L, lastOnActiveTime.get())
        Assert.assertTrue("Went active first, and then inactive",
                lastOnInactiveTime.get() > lastOnActiveTime.get())
    }

    fun givenLiveData_whenObserverLifecycleStops_thenShouldCallOnInactive() {

    }


}