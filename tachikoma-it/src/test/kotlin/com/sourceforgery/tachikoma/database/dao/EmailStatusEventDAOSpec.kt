package com.sourceforgery.tachikoma.database.dao

import com.sourceforgery.tachikoma.DAOHelper
import com.sourceforgery.tachikoma.MinimalBinder
import com.sourceforgery.tachikoma.TestBinder
import com.sourceforgery.tachikoma.common.Email
import com.sourceforgery.tachikoma.common.EmailStatus
import com.sourceforgery.tachikoma.database.objects.id
import com.sourceforgery.tachikoma.hk2.located
import java.time.Clock
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import org.glassfish.hk2.api.ServiceLocator
import org.glassfish.hk2.utilities.ServiceLocatorUtilities
import org.jetbrains.spek.api.Spek
import org.jetbrains.spek.api.dsl.describe
import org.jetbrains.spek.api.dsl.it
import org.junit.platform.runner.JUnitPlatform
import org.junit.runner.RunWith

@RunWith(JUnitPlatform::class)
internal class EmailStatusEventDAOSpec : Spek({
    lateinit var serviceLocator: ServiceLocator
    val emailStatusEventDAO: () -> EmailStatusEventDAO = located { serviceLocator }
    val daoHelper: () -> DAOHelper = located { serviceLocator }
    val clock: () -> Clock = located { serviceLocator }
    beforeEachTest {
        serviceLocator = ServiceLocatorUtilities.bind(
            TestBinder(),
            MinimalBinder(EmailStatusEventDAO::class.java)
        )
    }

    afterEachTest {
        serviceLocator.shutdown()
    }

    describe("EmailStatusEventDAO") {

        it("it should return all recent status events for specified account") {

            val authentication1 = daoHelper().createAuthentication("example.org")
            daoHelper().createEmailStatusEvent(
                authentication = authentication1,
                from = Email("from@example.org"),
                recipient = Email("recipient1@example.org"),
                emailStatus = EmailStatus.DELIVERED
            )
            daoHelper().createEmailStatusEvent(
                authentication = authentication1,
                from = Email("from@example.org"),
                recipient = Email("recipient2@example.org"),
                emailStatus = EmailStatus.DELIVERED
            )
            val event1 = daoHelper().createEmailStatusEvent(
                authentication = authentication1,
                from = Email("from@example.org"),
                recipient = Email("recipient3@example.org"),
                emailStatus = EmailStatus.DELIVERED,
                dateCreated = clock().instant().minus(3, ChronoUnit.DAYS)
            )
            val event2 = daoHelper().createEmailStatusEvent(
                authentication = authentication1,
                from = Email("from@example.org"),
                recipient = Email("recipient3@example.org"),
                emailStatus = EmailStatus.UNSUBSCRIBE,
                dateCreated = clock().instant().minus(2, ChronoUnit.DAYS)
            )

            val authentication2 = daoHelper().createAuthentication("example.com")
            daoHelper().createEmailStatusEvent(
                authentication = authentication2,
                from = Email("from@example.com"),
                recipient = Email("recipient@example.com"),
                emailStatus = EmailStatus.DELIVERED
            )

            val eventsTimeLimit = clock().instant().minus(4, ChronoUnit.DAYS)

            val emailStatusEvents = emailStatusEventDAO().getEvents(
                accountId = authentication1.account.id,
                instant = eventsTimeLimit,
                recipientEmail = Email("recipient3@example.org"),
                fromEmail = Email("from@example.org"),
                events = listOf(EmailStatus.DELIVERED, EmailStatus.UNSUBSCRIBE)
            )

            assertEquals(2, emailStatusEvents.size)
            assertEquals(event1.id, emailStatusEvents[0].id)
            assertEquals(event2.id, emailStatusEvents[1].id)
        }

        it("it should not return events outside time limit") {

            val authentication1 = daoHelper().createAuthentication("example.org")
            daoHelper().createEmailStatusEvent(
                authentication = authentication1,
                from = Email("from@example.org"),
                recipient = Email("recipient1@example.org"),
                emailStatus = EmailStatus.DELIVERED,
                dateCreated = clock().instant().minus(3, ChronoUnit.DAYS)

            )
            daoHelper().createEmailStatusEvent(
                authentication = authentication1,
                from = Email("from@example.org"),
                recipient = Email("recipient1@example.org"),
                emailStatus = EmailStatus.DELIVERED,
                dateCreated = clock().instant().minus(4, ChronoUnit.DAYS)

            )

            val eventsTimeLimit = clock().instant().minus(2, ChronoUnit.DAYS)

            val emailStatusEvents = emailStatusEventDAO().getEvents(
                accountId = authentication1.account.id,
                instant = eventsTimeLimit
            )

            assertEquals(0, emailStatusEvents.size)
        }
    }
})
