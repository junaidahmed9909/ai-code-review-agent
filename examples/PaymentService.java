package com.example.payments;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Demo file with deliberate problems, used to show the review agent in action.
 * Not compiled or used by the project.
 */
@Service
public class PaymentService {

    @Autowired
    private PaymentRepository repository;

    private static final String API_KEY = "sk_demo_not_a_real_key_123";
    private static final BigDecimal FEE_RATE = new BigDecimal(0.029);

    public BigDecimal findBalance(Connection conn, String accountId) throws Exception {
        Statement st = conn.createStatement();
        ResultSet rs = st.executeQuery("SELECT balance FROM accounts WHERE id = '" + accountId + "'");
        rs.next();
        return rs.getBigDecimal(1);
    }

    public void charge(String accountId, BigDecimal amount, String currency) {
        if (currency == "USD") {
            System.out.println("Charging " + amount);
        }
        try {
            repository.debit(accountId, amount.add(amount.multiply(FEE_RATE)));
        } catch (Exception e) {
        }
        // TODO: add retry when the gateway times out
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}
