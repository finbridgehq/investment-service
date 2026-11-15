package com.liquilabs.vankoo.investment.acceptance;

import com.liquilabs.vankoo.investment.domain.model.aggregates.Auction;
import com.liquilabs.vankoo.investment.domain.model.valueobjects.*;
import com.liquilabs.vankoo.investment.domain.services.AuctionPricingCalculator;
import com.liquilabs.vankoo.investment.domain.services.AuctionPricingCalculatorTest;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class FundingProgressSteps {
    private static final Instant NOW = Instant.parse("2026-09-07T17:00:00Z");
    private static final LocalDate DUE_DATE = LocalDate.of(2026, 11, 6);
    private static final BigDecimal MINIMUM_TICKET = new BigDecimal("500.00");

    private final AuctionPricingCalculator calculator = new AuctionPricingCalculator();
    private Auction auction;
    private BigDecimal fundingBefore;
    private int investments;

    @Given("que la operación está publicada en subasta")
    public void publishedAuction() {
        auction = new Auction(new InvoiceId("invoice-bdd"), new UserId("mype-bdd"),
                new Money(new BigDecimal("10000.00"), Currency.PEN), false,
                "20123456789", "Pagador S.A.", DUE_DATE);
        auction.evaluate("assessment-bdd", ScoreGrade.B, true, NOW);
        var calculation = calculator.calculate(AuctionPricingCalculatorTest.pricingParameters(),
                auction.getFundableAmount(), ScoreGrade.B, LocalDate.of(2026, 9, 7), DUE_DATE);
        var quote = auction.createQuote(calculation, NOW, Duration.ofHours(24));
        auction.acceptQuote(quote.getId(), NOW, Duration.ofDays(7), Duration.ofDays(1), ZoneId.of("America/Lima"));
        assertThat(auction.getStatus()).isEqualTo(AuctionStatus.PUBLISHED);
    }

    @Given("todavía tiene un saldo pendiente de fondeo")
    public void hasPendingBalance() {
        assertThat(remaining()).isPositive();
        fundingBefore = auction.getCurrentFunding().amount();
    }

    @When("se registra una nueva participación de un inversionista")
    public void registersInvestment() {
        invest(MINIMUM_TICKET);
    }

    @Then("el sistema actualiza el monto financiado")
    public void updatesFundedAmount() {
        assertThat(auction.getCurrentFunding().amount()).isEqualByComparingTo(fundingBefore.add(MINIMUM_TICKET));
        assertThat(auction.getStatus()).isEqualTo(AuctionStatus.FUNDING);
    }

    @Then("muestra el porcentaje de avance y el saldo pendiente")
    public void showsProgressAndPendingBalance() {
        BigDecimal target = auction.getTargetAmount().amount();
        BigDecimal progress = auction.getCurrentFunding().amount()
                .multiply(new BigDecimal("100"))
                .divide(target, 2, RoundingMode.HALF_UP);
        assertThat(progress).isGreaterThan(BigDecimal.ZERO).isLessThan(new BigDecimal("100"));
        assertThat(remaining()).isEqualByComparingTo(target.subtract(MINIMUM_TICKET));
    }

    @Given("que las participaciones registradas alcanzan el 100 % del monto objetivo")
    public void participationsReachTarget() {
        publishedAuction();
        invest(MINIMUM_TICKET);
    }

    @When("el sistema confirma el fondeo completo de la operación")
    public void confirmsFullFunding() {
        invest(remaining());
    }

    @Then("cambia su estado a {string}")
    public void changesStatusTo(String status) {
        assertThat(status).isEqualTo("Fondeada");
        assertThat(auction.getStatus()).isEqualTo(AuctionStatus.FULLY_FUNDED);
        assertThat(auction.isFunded()).isTrue();
        assertThat(remaining()).isZero();
    }

    @Then("ya no acepta nuevas participaciones")
    public void rejectsFurtherInvestments() {
        assertThatThrownBy(() -> invest(MINIMUM_TICKET))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not accepting investments");
        assertThat(auction.getPartitions()).hasSize(2);
    }

    private void invest(BigDecimal amount) {
        investments++;
        auction.addInvestment(new UserId("investor-" + investments), new Money(amount, Currency.PEN),
                MINIMUM_TICKET, "tx-bdd-" + investments, NOW.plusSeconds(investments));
    }

    private BigDecimal remaining() {
        return auction.getTargetAmount().amount().subtract(auction.getCurrentFunding().amount());
    }
}
