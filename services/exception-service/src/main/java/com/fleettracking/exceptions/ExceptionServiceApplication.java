package com.fleettracking.exceptions;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The fifth service, and the first that reads more than one topic.
 *
 * <p>It watches three streams and writes to a fourth. Positions tell it where a truck is and how
 * fast; statuses carry the reefer readings that no position event can; and the derived stream
 * carries what the tracking processor concluded — arrivals, departures and revised estimates —
 * which is what makes a projected late delivery knowable before the truck is late.
 *
 * <h2>Why a separate service rather than more rules inside the tracking processor</h2>
 *
 * <p>Every rule here could technically be evaluated where the positions are already being handled,
 * and it would save a round trip through a broker. Three things argue against it, and they are the
 * arguments this platform's architecture is made of.
 *
 * <p>The two have opposite shapes of load. The tracking processor handles every fix from every
 * truck and is the thing that has to scale to twelve partitions and beyond; this service handles
 * the same stream but produces a handful of events a day, and its own state is tiny. Bolting them
 * together means scaling the cheap thing because the expensive thing needs it.
 *
 * <p>They fail differently, and should be allowed to. A bug in a new SLA rule that throws on an
 * unexpected manifest should not stop position history being written — history is the record, and
 * losing it loses something unrecoverable, while a missed exception is recomputable from the stream
 * that is still there.
 *
 * <p>And an SLA rule is the part of this system most likely to change. Thresholds get argued about,
 * rules get added, a customer negotiates a different tolerance. That belongs in something that can
 * be redeployed without touching the consumer holding the fleet's history.
 *
 * <h2>No web server, and what keeps it alive</h2>
 *
 * <p>The same shape as the tracking processor: {@code web-application-type: none}, and the Kafka
 * listener containers supply the non-daemon threads that stop the JVM exiting the moment
 * {@code main} returns. There is one further non-daemon dependency here — the scheduled sweep the
 * signal-loss rule needs — but the listeners are what actually hold the process open.
 */
@SpringBootApplication
public class ExceptionServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(ExceptionServiceApplication.class, args);
  }
}
